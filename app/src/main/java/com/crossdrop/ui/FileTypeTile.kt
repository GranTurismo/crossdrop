package com.crossdrop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crossdrop.core.i18n.tr
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Amber
import com.crossdrop.ui.theme.GlassHigh
import com.crossdrop.ui.theme.Sky
import com.crossdrop.ui.theme.SkyText
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.Violet
import com.crossdrop.ui.theme.VioletText

/** What a file is, for its tile and the inbox's storage breakdown. */
enum class FileKind {
    PHOTO, VIDEO, AUDIO, DOC, ARCHIVE, APP, FOLDER, OTHER;

    /** Display name, read at call time so a language change shows up without restarting. */
    val label: String
        get() = when (this) {
            PHOTO -> tr("Photos")
            VIDEO -> tr("Videos")
            AUDIO -> tr("Audio")
            DOC -> tr("Documents")
            ARCHIVE -> tr("Archives")
            APP -> tr("Apps")
            FOLDER -> tr("Folders")
            OTHER -> tr("Other")
        }

    val icon: ImageVector
        get() = when (this) {
            PHOTO -> CdIcons.Photo
            VIDEO -> CdIcons.Video
            AUDIO -> CdIcons.Music
            DOC -> CdIcons.Doc
            ARCHIVE -> CdIcons.Archive
            APP -> CdIcons.Apk
            FOLDER -> CdIcons.Folder
            OTHER -> CdIcons.File
        }

    /** Icon colour on the tile. */
    val tint: Color
        get() = when (this) {
            PHOTO, APP -> Accent
            VIDEO -> VioletText
            AUDIO, ARCHIVE -> SkyText
            DOC, FOLDER -> Amber
            OTHER -> TextSecondary
        }

    /** Solid swatch for charts and legends. */
    val swatch: Color
        get() = when (this) {
            PHOTO, APP -> Accent
            VIDEO -> Violet
            AUDIO, ARCHIVE, OTHER -> Sky
            DOC, FOLDER -> Amber
        }

    val tileBg: Color
        get() = when (this) {
            OTHER -> GlassHigh
            VIDEO -> Violet.copy(alpha = 0.16f)
            else -> swatch.copy(alpha = 0.14f)
        }

    companion object {
        fun of(fileName: String, isDir: Boolean = false): FileKind {
            if (isDir) return FOLDER
            return when (fileName.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "svg", "bmp", "avif", "dng", "raw" -> PHOTO
                "mp4", "mkv", "mov", "webm", "avi", "m4v", "3gp" -> VIDEO
                "mp3", "m4a", "flac", "ogg", "wav", "opus", "aac" -> AUDIO
                "pdf", "doc", "docx", "txt", "md", "epub", "rtf", "odt", "xls", "xlsx", "csv",
                "ppt", "pptx", "key", "pages", "numbers", "fig" -> DOC
                "zip", "rar", "7z", "tar", "gz", "tgz", "xz", "bz2" -> ARCHIVE
                "apk", "aab", "xapk", "ipa", "dmg", "exe", "msi" -> APP
                else -> OTHER
            }
        }
    }
}

/** Tinted tile with the file's category icon — the app's file-type glyph. */
@Composable
fun FileTypeTile(fileName: String, size: Dp = 42.dp, modifier: Modifier = Modifier, isDir: Boolean = false) {
    val kind = FileKind.of(fileName, isDir || fileName == "dir")
    IconTile(
        kind.icon, kind.tint, modifier, bg = kind.tileBg,
        size = size, radius = size * 0.32f, iconSize = size * 0.46f,
    )
}
