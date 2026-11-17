package com.crossdrop.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's stroke icon set — 24-unit viewBox, round caps/joins, drawn from
 * the same SVG path data as the design. Vectors keep the app crisp without
 * the material-icons artifact; tint comes from [CdIcon].
 */
object CdIcons {
    // navigation
    val Radar by lazy { icon(circle(12f, 12f, 9f), circle(12f, 12f, 4.5f)) }
    val Transfers by lazy { icon("M7 4v16M4 7l3-3 3 3M17 20V4M14 17l3 3 3-3") }
    val Inbox by lazy { icon("M3 13l3-8h12l3 8v6H3z", "M3 13h5l1 2h6l1-2h5") }
    val Sliders by lazy { icon("M4 7h10M18 7h2M4 17h4M12 17h8", circle(16f, 7f, 2f), circle(10f, 17f, 2f)) }

    // devices
    val Phone by lazy { icon(rect(7f, 2.5f, 10f, 19f, 2.5f), "M11 18.5h2", stroke = 1.8f) }
    val Laptop by lazy { icon(rect(4f, 5f, 16f, 11f, 1.5f), "M2 19h20", stroke = 1.8f) }
    val Tablet by lazy { icon(rect(4.5f, 2.5f, 15f, 19f, 2.5f), "M11 18.5h2", stroke = 1.8f) }
    val Monitor by lazy { icon(rect(3f, 4f, 18f, 12f, 1.5f), "M9 20h6M12 16v4", stroke = 1.8f) }
    val Drive by lazy { icon(rect(3f, 13f, 18f, 7f, 2f), "M5 13l2.5-8h9L19 13M7 16.5h.01M10 16.5h.01") }

    // radios
    val Wifi by lazy { icon("M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 19.5h.01") }
    val Bluetooth by lazy { icon("M7 7l10 10-5 4V3l5 4L7 17", stroke = 2.1f) }

    // content types
    val Photo by lazy { icon(rect(3f, 4f, 18f, 16f, 3f), circle(9f, 10f, 1.8f), "M21 16l-5-5-9 9", stroke = 1.8f) }
    val Doc by lazy { icon("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5M9 13h6M9 17h4", stroke = 1.8f) }
    val Video by lazy { icon(rect(3f, 6f, 13f, 12f, 2f), "M16 10l5-3v10l-5-3") }
    val Archive by lazy { icon(FOLDER, "M12 11v1M12 14v1") }
    val Folder by lazy { icon(FOLDER, stroke = 1.7f) }
    val Music by lazy { icon("M9 18V5l11-2v13", circle(6f, 18f, 3f), circle(17f, 16f, 3f)) }
    val Apk by lazy { icon(rect(5f, 9f, 14f, 11f, 2.5f), "M8 9a4 4 0 0 1 8 0M9 5L7.5 3M15 5l1.5-2M10 14.5h.01M14 14.5h.01") }
    val File by lazy { icon("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5", stroke = 1.8f) }

    // actions & glyphs
    val Qr by lazy {
        icon(
            rect(3f, 3f, 7f, 7f, 1f), rect(14f, 3f, 7f, 7f, 1f), rect(3f, 14f, 7f, 7f, 1f),
            "M14 14h3v3h-3zM20 14v.01M14 20h.01M17 20h4v-3", stroke = 1.8f,
        )
    }
    val ArrowRight by lazy { icon("M5 12h14M13 6l6 6-6 6", stroke = 2.2f) }
    val ArrowUp by lazy { icon("M12 19V5M6 11l6-6 6 6", stroke = 2.2f) }
    val ArrowDown by lazy { icon("M12 5v14M6 13l6 6 6-6", stroke = 2.2f) }
    val ChevronLeft by lazy { icon("M15 6l-6 6 6 6", stroke = 2f) }
    val ChevronRight by lazy { icon("M9 6l6 6-6 6", stroke = 2.2f) }
    val Check by lazy { icon("M5 12.5l4.5 4.5L19 7", stroke = 2.4f) }
    val Close by lazy { icon("M6 6l12 12M18 6L6 18", stroke = 2.2f) }
    val Lock by lazy { icon(rect(5f, 11f, 14f, 10f, 2f), "M8 11V8a4 4 0 0 1 8 0v3", stroke = 2.2f) }
    val Shield by lazy { icon("M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6z", "M8.5 12l2.5 2.5 4.5-5") }
    val Trash by lazy { icon("M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3", stroke = 2f) }
    val Pencil by lazy { icon("M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4") }
    val Copy by lazy { icon(rect(8f, 8f, 12f, 12f, 2.5f), "M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2", stroke = 2.2f) }
    val Help by lazy { icon(circle(12f, 12f, 9f), "M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .8-1 1.5v.4M12 17h.01") }
    val Globe by lazy { icon(circle(12f, 12f, 9f), "M3 12h18M12 3c2.5 2.7 3.8 5.7 3.8 9s-1.3 6.3-3.8 9c-2.5-2.7-3.8-5.7-3.8-9S9.5 5.7 12 3z") }
    val Download by lazy { icon("M12 4v12M6 11l6 6 6-6M5 20h14") }
    val Upload by lazy { icon("M12 20V8M6 13l6-6 6 6M5 4h14") }
    val Play by lazy { filled("M8 5.5v13l10.5-6.5z") }
    val Bolt by lazy { icon("M13 3L5 13.5h6L10 21l8-10.5h-6z") }
    val Contacts by lazy { icon(circle(12f, 9f, 3.5f), "M5 20c.8-3.5 3.6-5.5 7-5.5s6.2 2 7 5.5") }
    val Camera by lazy { icon("M4 8a2 2 0 0 1 2-2h2l1.5-2h5L16 6h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z", circle(12f, 12.5f, 3.5f)) }
    val Note by lazy { icon("M5 6h14M5 11h14M5 16h9", stroke = 2f) }
    val Swap by lazy { icon("M7 4L3 8l4 4M3 8h13M17 12l4 4-4 4M21 16H8", stroke = 2f) }
    val Live by lazy { icon(circle(12f, 12f, 3f), "M6.5 6.5a8 8 0 0 0 0 11M17.5 6.5a8 8 0 0 1 0 11", stroke = 2f) }
    val Search by lazy { icon(circle(11f, 11f, 6.5f), "M16 16l4.5 4.5", stroke = 2.2f) }
    val Wrench by lazy { icon("M14.5 6.5a4 4 0 0 0-5.3 5.3L4 17l3 3 5.2-5.2a4 4 0 0 0 5.3-5.3l-2.5 2.5-2.5-.5-.5-2.5z") }
}

/** Renders a [CdIcons] vector in [tint]. Decorative unless [contentDescription] is set. */
@Composable
fun CdIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    contentDescription: String? = null,
) {
    Icon(icon, contentDescription, modifier.size(size), tint)
}

// ------------------------------------------------------------- builders --

private const val FOLDER = "M4 7a2 2 0 0 1 2-2h4l2 2h6a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z"

private fun icon(vararg paths: String, stroke: Float = 1.9f): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

private fun filled(d: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
        .build()

private fun n(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

/** SVG `<rect x y width height rx>` as path data. */
private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float): String {
    val iw = w - 2 * r; val ih = h - 2 * r
    return "M${n(x + r)} ${n(y)}h${n(iw)}a${n(r)} ${n(r)} 0 0 1 ${n(r)} ${n(r)}v${n(ih)}" +
        "a${n(r)} ${n(r)} 0 0 1 ${n(-r)} ${n(r)}h${n(-iw)}a${n(r)} ${n(r)} 0 0 1 ${n(-r)} ${n(-r)}" +
        "v${n(-ih)}a${n(r)} ${n(r)} 0 0 1 ${n(r)} ${n(-r)}z"
}

/** SVG `<circle cx cy r>` as path data. */
private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${n(cx - r)} ${n(cy)}a${n(r)} ${n(r)} 0 1 0 ${n(2 * r)} 0a${n(r)} ${n(r)} 0 1 0 ${n(-2 * r)} 0z"
