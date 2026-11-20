package com.crossdrop.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.Offer
import com.crossdrop.net.AppReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Apps another Android phone sent: staged privately, then installed one by one through Android's own
 * installer (it shows its confirmation and checks the app like any install from outside Google Play).
 * What the app is (name, icon, version, package) is read from the APK itself, never taken from the sender.
 */
class AppInbox(private val context: Context, private val scope: CoroutineScope) : AppReceiver {

    enum class Status { READY, INSTALLING, INSTALLED, FAILED }

    data class Item(
        val pkg: String,
        val label: String,
        val versionName: String?,
        val bytes: Long,
        val files: List<File>,
        /** The version already on this phone, if any. */
        val installedVersion: String? = null,
        val status: Status = Status.READY,
        val error: String? = null,
    ) {
        val base: File get() = files.first()
    }

    data class Batch(val id: String, val peerName: String, val items: List<Item>)

    private val _state = MutableStateFlow<Batch?>(null)
    /** The apps of the last receive, until the person is done with them. */
    val state: StateFlow<Batch?> = _state

    private val root = File(context.filesDir, "apps-in")
    private val pm get() = context.packageManager

    override fun stagingFile(offer: Offer, index: Int, meta: FileMeta): File {
        val dir = dirFor(offer.transferId)
        // A new receive sweeps out what failed or was abandoned before (never the batch on screen).
        if (index == 0) {
            val keep = _state.value?.id?.let(::dirFor)
            val now = System.currentTimeMillis()
            root.listFiles()?.filter { it != dir && it != keep && now - it.lastModified() > STALE_MS }
                ?.forEach { it.deleteRecursively() }
        }
        return File(dir, "%03d.apk".format(index))
    }

    private fun dirFor(transferId: String) = File(root, transferId.filter { it.isLetterOrDigit() || it == '-' }.take(40))

    override suspend fun received(peerName: String, offer: Offer, files: List<File>) {
        // The sender lists each app's base APK first, then its splits.
        val groups = LinkedHashMap<String, MutableList<File>>()
        offer.files.forEachIndexed { i, meta ->
            val f = files.getOrNull(i)?.takeIf { it.isFile } ?: return@forEachIndexed
            groups.getOrPut(meta.pkg ?: meta.name) { mutableListOf() } += f
        }
        val items = groups.values.mapNotNull { apks -> describe(apks) }
        if (items.isEmpty()) {
            groups.values.flatten().forEach { it.delete() }
            return
        }
        _state.value = Batch(offer.transferId, peerName, items)
    }

    /** What the APKs really are, from the base APK's manifest; null when it isn't an installable app. */
    private fun describe(apks: List<File>): Item? {
        val base = apks.first()
        @Suppress("DEPRECATION")
        val info = runCatching { pm.getPackageArchiveInfo(base.path, 0) }.getOrNull() ?: return null
        val ai = info.applicationInfo?.apply { sourceDir = base.path; publicSourceDir = base.path } ?: return null
        val installed = installedVersion(info.packageName)
        return Item(
            pkg = info.packageName,
            label = runCatching { ai.loadLabel(pm).toString() }.getOrNull()?.ifBlank { null } ?: info.packageName,
            versionName = info.versionName,
            bytes = apks.sumOf { it.length() },
            files = apks,
            installedVersion = installed,
        )
    }

    private fun installedVersion(pkg: String): String? =
        runCatching { pm.getPackageInfo(pkg, 0).versionName ?: "" }.getOrNull()

    /** The person may have installed or removed one of these apps meanwhile: check again (the screen calls it on resume). */
    fun refresh() = _state.update { b ->
        b?.copy(items = b.items.map { item ->
            val now = installedVersion(item.pkg)
            when {
                item.status == Status.INSTALLING -> item
                item.status == Status.INSTALLED && now == null -> item.copy(status = Status.READY, installedVersion = null)
                else -> item.copy(installedVersion = now)
            }
        })
    }

    fun icon(item: Item) = runCatching {
        @Suppress("DEPRECATION")
        pm.getPackageArchiveInfo(item.base.path, 0)?.applicationInfo
            ?.apply { sourceDir = item.base.path; publicSourceDir = item.base.path }?.loadIcon(pm)
    }.getOrNull()

    /** Android lets an app install others only after the person allowed it once ("Install unknown apps"). */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || pm.canRequestPackageInstalls()

    /** The settings screen where the person allows CrossDrop to install apps. */
    fun allowIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))

    fun launchIntent(item: Item): Intent? = pm.getLaunchIntentForPackage(item.pkg)

    /** Hand [pkg]'s APKs to Android's installer in one session; its confirmation dialog follows. */
    fun install(pkg: String) {
        val item = _state.value?.items?.firstOrNull { it.pkg == pkg } ?: return
        if (item.status == Status.INSTALLING) return
        update(pkg) { it.copy(status = Status.INSTALLING, error = null) }
        scope.launch(Dispatchers.IO) {
            try {
                ensureReceiver()
                val installer = pm.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(item.pkg)
                    setSize(item.bytes)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    item.files.forEachIndexed { i, f ->
                        session.openWrite("apk$i.apk", 0, f.length()).use { out ->
                            f.inputStream().use { it.copyTo(out, 256 * 1024) }
                            session.fsync(out)
                        }
                    }
                    val status = Intent(ACTION_STATUS).setPackage(context.packageName).putExtra(EXTRA_PKG, item.pkg)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
                }
            } catch (e: Exception) {
                Log.w(TAG, "install of $pkg failed to start", e)
                update(pkg) { it.copy(status = Status.FAILED, error = e.message) }
            }
        }
    }

    /** Done with this batch: whatever wasn't installed is deleted. */
    fun dismiss() {
        val batch = _state.value ?: return
        _state.value = null
        scope.launch(Dispatchers.IO) { dirFor(batch.id).deleteRecursively() }
    }

    /** Leftovers of receives that never finished, or were never dismissed (the process died). */
    fun cleanUp() {
        if (_state.value != null) return
        runCatching { root.deleteRecursively() }
    }

    private fun update(pkg: String, change: (Item) -> Item) = _state.update { b ->
        b?.copy(items = b.items.map { if (it.pkg == pkg) change(it) else it })
    }

    private var receiverRegistered = false

    @Synchronized
    private fun ensureReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(context.applicationContext, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onStatus(intent)
        }, IntentFilter(ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun onStatus(intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) { update(pkg) { it.copy(status = Status.FAILED) }; return }
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { e -> update(pkg) { it.copy(status = Status.FAILED, error = e.message) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // The files stay until "Done" (dismiss deletes the batch): removed again meanwhile, it can be reinstalled.
                update(pkg) { it.copy(status = Status.INSTALLED, error = null, installedVersion = it.versionName ?: "") }
            }
            // The person said no in Android's dialog: back to "Install".
            PackageInstaller.STATUS_FAILURE_ABORTED -> update(pkg) { it.copy(status = Status.READY) }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.i(TAG, "install of $pkg: status $status $message")
                update(pkg) { it.copy(status = Status.FAILED, error = message) }
            }
        }
    }

    private companion object {
        const val TAG = "AppInbox"
        const val ACTION_STATUS = "com.crossdrop.apps.INSTALL_STATUS"
        const val EXTRA_PKG = "pkg"
        const val STALE_MS = 10 * 60_000L
    }
}
