package com.crossdrop.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.protocol.ItemCategory
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * The phone's photos and videos as MediaStore lists them, for "Switch phones" and backups.
 *
 * Android hides where a photo was taken (EXIF GPS) from apps that read it without
 * `ACCESS_MEDIA_LOCATION` and an "original" URI. A move or backup must keep the photo exactly as it is,
 * so [open] asks for the original whenever that permission is granted.
 */
object MediaLibrary {

    class Item(
        /** MediaStore id, prefixed "p:" (photo) or "v:" (video). */
        val id: String,
        val uri: Uri,
        val meta: FileMeta,
        /** When it was taken (or last modified), epoch ms; 0 when unknown. */
        val takenAt: Long,
        /** MediaStore's DATE_MODIFIED, seconds: changes when the item is edited in place. */
        val modified: Long,
    ) {
        /** Stable for this exact version of the item: an edit makes a new key (and a new backup). */
        val versionKey: String get() = "$id:$modified"
    }

    /** What reading the whole library needs. */
    fun libraryPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    /** What to ask for: the library plus the photos' locations (Android shows no separate question for those). */
    fun readPermissions(): Array<String> = libraryPermissions() + Manifest.permission.ACCESS_MEDIA_LOCATION

    fun canReadLibrary(context: Context): Boolean = libraryPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun canReadLocations(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Newest first. Items MediaStore reports as empty are skipped. [relativePath] limits it to one folder (debug tests). */
    fun query(context: Context, video: Boolean, relativePath: String? = null): List<Item> {
        val uri = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val out = ArrayList<Item>()
        runCatching {
            val where = relativePath?.let { "${MediaStore.MediaColumns.RELATIVE_PATH} = ?" }
            val args = relativePath?.let { arrayOf(it) }
            context.contentResolver.query(uri, proj, where, args, "${MediaStore.MediaColumns.DATE_TAKEN} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val size = c.getLong(2)
                    if (size <= 0) continue
                    val modified = c.getLong(5)
                    val taken = c.getLong(4).takeIf { it > 0 } ?: (modified * 1000)
                    out += Item(
                        (if (video) "v:" else "p:") + id,
                        ContentUris.withAppendedId(uri, id),
                        FileMeta(
                            c.getString(1) ?: "item_$id", size, c.getString(3),
                            cat = if (video) ItemCategory.VIDEO else ItemCategory.PHOTO,
                            mtime = taken.takeIf { it > 0 },
                        ),
                        taken.coerceAtLeast(0),
                        modified,
                    )
                }
            }
        }
        return out
    }

    /** The item's bytes exactly as stored, location included when Android lets us read it. */
    fun open(context: Context, uri: Uri): InputStream {
        val resolver = context.contentResolver
        if (canReadLocations(context)) {
            runCatching { resolver.openInputStream(MediaStore.setRequireOriginal(uri)) }.getOrNull()?.let { return it }
        }
        return resolver.openInputStream(uri) ?: throw FileNotFoundException("$uri")
    }
}
