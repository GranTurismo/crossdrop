package com.crossdrop.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.ItemCategory
import com.crossdrop.core.transfer.OutgoingFile
import java.io.File

/**
 * The apps a person installed themselves, as Android's launcher lists them: what "Apps" offers to send.
 *
 * Only launchable apps are visible to CrossDrop (a `<queries>` launcher intent in the manifest), so the app
 * never needs the "see all apps" permission. System apps are left out: the other phone has its own. An app
 * is its base APK plus any split APKs (most Play installs: language, screen density, CPU); all of them go,
 * and the receiver installs them together.
 */
object InstalledApps {

    class App(
        val pkg: String,
        val label: String,
        val versionName: String?,
        val apks: List<File>,
        val info: ApplicationInfo,
    ) {
        val bytes: Long get() = apks.sumOf { it.length() }
        fun icon(pm: PackageManager): Drawable = info.loadIcon(pm)
    }

    /** Sorted by name. Reads only package metadata; nothing is opened until something is sent. */
    fun list(context: Context): List<App> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pkgs = runCatching { pm.queryIntentActivities(launcher, 0) }.getOrDefault(emptyList())
            .map { it.activityInfo.packageName }.distinct()
        return pkgs.mapNotNull { pkg ->
            if (pkg == context.packageName) return@mapNotNull null
            val pi = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val ai = pi.applicationInfo ?: return@mapNotNull null
            if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@mapNotNull null
            val apks = (listOf(ai.sourceDir) + ai.splitSourceDirs.orEmpty()).map(::File).filter { it.canRead() }
            if (apks.isEmpty()) return@mapNotNull null
            App(pkg, ai.loadLabel(pm).toString().ifBlank { pkg }, pi.versionName, apks, ai)
        }.sortedBy { it.label.lowercase() }
    }

    /** What goes over the wire for [apps]: every APK, named after the app, tagged with its package. */
    fun outgoing(apps: List<App>): List<OutgoingFile> = apps.flatMap { app ->
        val base = app.label.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { app.pkg }
        app.apks.map { apk ->
            val split = apk.name.removeSuffix(".apk").removePrefix("split_").takeIf { apk.name != "base.apk" }
            // Only letters, digits and `._ -` survive the receiving inbox's name rules.
            val name = if (split == null) "$base.apk" else "$base - $split.apk"
            OutgoingFile(
                FileMeta(name, apk.length(), MIME, cat = ItemCategory.APP, pkg = app.pkg),
            ) { apk.inputStream() }
        }
    }

    const val MIME = "application/vnd.android.package-archive"
}
