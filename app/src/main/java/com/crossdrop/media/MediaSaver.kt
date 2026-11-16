package com.crossdrop.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * Writes photos and videos into the shared gallery (MediaStore) and decodes pictures for display.
 * Inserting our own media needs no permission on Android 10+.
 */
object MediaSaver {

    fun isVideo(name: String, mime: String?): Boolean =
        mime?.startsWith("video/") == true ||
            name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "m4v", "3gp", "webm", "mkv")

    /**
     * Copy [file] into the gallery under [folder] (e.g. "DCIM/CrossDrop"), dated [takenMs].
     * Returns the new item's URI, or null when the copy failed.
     */
    fun saveToGallery(context: Context, file: File, name: String, mime: String?, takenMs: Long?, folder: String): Uri? {
        val video = isVideo(name, mime)
        val type = mime ?: guessMime(name, video)
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, type)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (video && folder.startsWith("Pictures/")) "Movies/" + folder.removePrefix("Pictures/") else folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            takenMs?.let {
                put(MediaStore.MediaColumns.DATE_TAKEN, it)
                put(MediaStore.MediaColumns.DATE_MODIFIED, it / 1000)
            }
        }
        if (takenMs != null && !video) stampCaptureTime(file, name, takenMs)
        val resolver = context.contentResolver
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } } != null
        }.getOrDefault(false)
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        runCatching {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        // Publishing rescans the file and DATE_TAKEN comes back from its metadata (null without EXIF):
        // set the original capture time once more so the gallery sorts it where it belongs.
        takenMs?.let { t ->
            runCatching {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, t) }, null, null)
            }
        }
        return uri
    }

    /**
     * The gallery takes a photo's date from its EXIF when the item is published, but only trusts an
     * EXIF time that carries a UTC offset (otherwise it compares it with the file's modification
     * time, which for an imported copy is "now", and gives up). So a JPEG gets its original time and
     * an offset written in when they're missing — on our staged copy, never on the sender's original.
     */
    private fun stampCaptureTime(file: File, name: String, takenMs: Long) {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext != "jpg" && ext != "jpeg") return
        runCatching {
            val exif = android.media.ExifInterface(file.path)
            val fmt = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
            val existing = exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL)
            val hasOffset = exif.getAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL) != null
            if (existing != null && hasOffset) return
            val whenMs = existing?.let { runCatching { fmt.parse(it)?.time }.getOrNull() } ?: takenMs
            val tz = java.util.TimeZone.getDefault().getOffset(whenMs) / 60_000
            val offset = "%s%02d:%02d".format(if (tz < 0) "-" else "+", Math.abs(tz) / 60, Math.abs(tz) % 60)
            if (existing == null) {
                val stamp = fmt.format(java.util.Date(takenMs))
                exif.setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
                exif.setAttribute(android.media.ExifInterface.TAG_DATETIME, stamp)
            }
            exif.setAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            exif.setAttribute(android.media.ExifInterface.TAG_OFFSET_TIME, offset)
            exif.saveAttributes()
        }
    }

    private fun guessMime(name: String, video: Boolean): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "heic", "heif" -> "image/heic"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "dng" -> "image/x-adobe-dng"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> if (video) "video/mp4" else "image/jpeg"
    }

    /** Decode a picture no larger than about [maxPx] on its long side; a video gets its first frame. */
    fun loadImage(file: File, maxPx: Int): ImageBitmap? {
        if (isVideo(file.name, null)) {
            return runCatching {
                ThumbnailUtils.createVideoThumbnail(file, Size(maxPx, maxPx), null).asImageBitmap()
            }.getOrNull()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return rotateByExif(file, bmp).asImageBitmap()
    }

    private fun rotateByExif(file: File, bmp: Bitmap): Bitmap {
        val deg = runCatching {
            when (android.media.ExifInterface(file.path).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL,
            )) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (deg == 0f) return bmp
        val m = android.graphics.Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

}
