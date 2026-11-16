package com.crossdrop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.DangerText
import com.crossdrop.ui.theme.Glass
import com.crossdrop.ui.theme.GlassHigh
import com.crossdrop.ui.theme.GlassLow
import com.crossdrop.ui.theme.GlassMid
import com.crossdrop.ui.theme.GlassRaised
import com.crossdrop.ui.theme.Hairline
import com.crossdrop.ui.theme.HairlineSoft
import com.crossdrop.ui.theme.HairlineStrong
import com.crossdrop.ui.theme.Ink
import com.crossdrop.ui.theme.InkSheet
import com.crossdrop.ui.theme.OnAccent
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextFaint
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.theme.monoStyle

/**
 * Space the shell's floating nav covers at the bottom of a tab. Scrolling
 * screens add it to their bottom content padding so the last row can scroll
 * clear of the glass bar. Zero where there is no floating nav (desktop).
 */
val LocalNavInset = compositionLocalOf { 0.dp }

/** Border of grouped list cards — between [HairlineSoft] and [Hairline]. */
val GroupBorder = Color.White.copy(alpha = 0.07f)

// ------------------------------------------------------------- surfaces --

/** Translucent rounded card — the design's basic surface. [glow] adds a soft coloured shadow. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 26.dp,
    fill: Color = Glass,
    border: Color? = Hairline,
    glow: Color? = null,
    glowRadius: Dp = 40.dp,
    glowOffsetY: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .then(
                if (glow != null) Modifier.dropShadow(
                    shape, Shadow(glowRadius, glow, offset = DpOffset(0.dp, glowOffsetY)),
                ) else Modifier,
            )
            .clip(shape)
            // A drop shadow paints its whole blurred shape behind the card; an ink
            // base keeps it from glowing through a translucent fill (CSS box-shadow
            // only ever shows outside the box).
            .then(if (glow != null && fill.alpha < 1f) Modifier.background(Ink) else Modifier)
            .background(fill)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .then(
                if (onClick != null) Modifier.clickable(
                    onClickLabel = onClickLabel, role = Role.Button, onClick = onClick,
                ) else Modifier,
            )
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** Grouped list: rows inside one card, split by [RowSeparator]s. */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    content: @Composable ColumnScope.() -> Unit,
) = GlassCard(
    modifier.fillMaxWidth(), radius = 26.dp, fill = GlassLow, border = GroupBorder,
    contentPadding = contentPadding, content = content,
)

/** 1dp hairline between rows of a [GroupCard]. */
@Composable
fun RowSeparator(modifier: Modifier = Modifier, color: Color = HairlineSoft) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

// ----------------------------------------------------------------- text --

/** Big Sora screen title (tabs). */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, size: Int = 34) {
    Text(
        text, style = displayStyle(size, tracking = -0.04f).copy(lineHeight = size.sp),
        modifier = modifier.semantics { heading() },
    )
}

/** Small uppercase group label ("TODAY", "DISCOVERY"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.08.em, modifier = modifier.padding(horizontal = 4.dp).semantics { heading() },
    )
}

/** Quiet explanatory line under a section, optionally led by an icon. */
@Composable
fun FootNote(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) CdIcon(icon, TextFaint, Modifier.padding(top = 1.dp), size = 16.dp)
        Text(text, color = TextFaint, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

// -------------------------------------------------------------- headers --

/** Overlay header: round glass back button + Sora title, optional subtitle and trailing actions. */
@Composable
fun BackHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backLabel: String = "Back",
    titleSize: Int = 22,
    subtitle: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CircleButton(CdIcons.ChevronLeft, backLabel, onBack, iconSize = 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title, style = displayStyle(titleSize), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            subtitle?.invoke()
        }
        trailing()
    }
}

// -------------------------------------------------------------- buttons --

/** Round icon-only button (44dp touch target by default). */
@Composable
fun CircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    fill: Color = GlassRaised,
    tint: Color = TextPrimary,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { CdIcon(icon, tint, size = iconSize) }
}

enum class PillStyle { Accent, Glass, Outline, Danger }

/** Full-height pill button — primary (accent), secondary (glass/outline) or destructive. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Accent,
    height: Dp = 54.dp,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    fontSize: Int = 15,
    glow: Boolean = false,
) {
    val shape = RoundedCornerShape(50)
    val (bg, fg) = when (style) {
        PillStyle.Accent -> Accent to OnAccent
        PillStyle.Glass -> GlassRaised to TextPrimary
        PillStyle.Outline -> GlassRaised to TextPrimary
        PillStyle.Danger -> Color(0x1FFF6B6B) to DangerText
    }
    Row(
        modifier
            .heightIn(min = height)
            .alpha(if (enabled) 1f else 0.45f)
            .then(
                if (glow && style == PillStyle.Accent && enabled)
                    Modifier.dropShadow(shape, Shadow(36.dp, Accent.copy(alpha = 0.35f), offset = DpOffset(0.dp, 10.dp)))
                else Modifier,
            )
            .clip(shape)
            .background(bg)
            .then(if (style == PillStyle.Outline) Modifier.border(1.dp, HairlineStrong, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            CdIcon(icon, fg, size = 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Compact pill (36–40dp) for in-card actions: "Open", "Copy", "Clear". */
@Composable
fun ChipButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    fg: Color = TextPrimary,
    bg: Color = GlassHigh,
    border: Color? = null,
    height: Dp = 36.dp,
    fontSize: Int = 13,
    contentDescription: String? = null,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.height(height).clip(shape).background(bg)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) CdIcon(icon, fg, size = 15.dp)
        Text(text, color = fg, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Copies [value] to the clipboard; the label flips to "Copied" as confirmation. */
@Composable
fun CopyButton(
    value: String,
    modifier: Modifier = Modifier,
    label: String = "Copy",
    accent: Boolean = false,
    height: Dp = 36.dp,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }
    ChipButton(
        text = if (copied) "Copied" else label,
        onClick = { clipboard.setText(AnnotatedString(value)); copied = true },
        modifier = modifier,
        icon = if (copied) CdIcons.Check else CdIcons.Copy,
        fg = if (accent) OnAccent else TextPrimary,
        bg = if (accent) Accent else GlassHigh,
        height = height,
    )
}

// ------------------------------------------------------------- controls --

/** The design's 52×32 switch. Exposed to accessibility as a real switch. */
@Composable
fun CdSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val thumbX by animateDpAsState(if (checked) 20.dp else 0.dp, label = "thumb")
    val track by animateColorAsState(if (checked) Accent else Color.White.copy(alpha = 0.14f), label = "track")
    val thumb by animateColorAsState(if (checked) OnAccent else TextSecondary, label = "thumbColor")
    Box(
        modifier.size(52.dp, 32.dp).alpha(if (enabled) 1f else 0.5f).clip(RoundedCornerShape(16.dp)).background(track)
            .then(
                if (onCheckedChange != null)
                    Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                else Modifier,
            )
            .padding(3.dp),
    ) {
        Box(Modifier.offset(x = thumbX).size(26.dp).clip(CircleShape).background(thumb))
    }
}

/** Settings-style row: optional icon, title + subtitle, trailing switch. */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) CdIcon(icon, TextPrimary, size = 22.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextDim, fontSize = 12.sp)
        }
        // The row owns the toggle semantics; the switch is only the visual.
        CdSwitch(checked, onCheckedChange = null)
    }
}

/** Equal-width segmented tabs inside a soft track (Mac / Windows / Browser). */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Glass).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (on) TextPrimary else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, color = if (on) Ink else TextSecondary, fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

/** Filter chip: filled light when selected, outlined otherwise. */
@Composable
fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.height(36.dp).clip(shape)
            .background(if (selected) TextPrimary else Color.Transparent)
            .then(if (!selected) Modifier.border(1.dp, HairlineStrong, shape) else Modifier)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = if (selected) 16.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = if (selected) Ink else TextSecondary, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

// ------------------------------------------------------------ indicators --

/** Small status dot with an optional glow. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 7.dp, glow: Boolean = true) {
    Box(
        modifier.size(size)
            .then(if (glow) Modifier.dropShadow(CircleShape, Shadow(10.dp, color)) else Modifier)
            .clip(CircleShape).background(color),
    )
}

/** Rounded status label: "Completed", "Failed", "PIN protected". */
@Composable
fun StatusPill(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Header chip: dot + label in a glass capsule ("Home Wi-Fi · local only"). */
@Composable
fun GlassChip(text: String, modifier: Modifier = Modifier, dot: Color? = Accent, fg: Color = TextSecondary) {
    Row(
        modifier.height(34.dp).clip(RoundedCornerShape(50)).background(GlassMid)
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (dot != null) Dot(dot)
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Monospace tag (cipher names). */
@Composable
fun MonoTag(text: String) {
    Text(
        text, style = monoStyle(11, TextSecondary),
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(GlassMid).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** Tinted rounded tile holding an icon (file types, list leads). */
@Composable
fun IconTile(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    bg: Color = tint.copy(alpha = 0.15f),
    size: Dp = 44.dp,
    radius: Dp = 14.dp,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(radius)).background(bg),
        contentAlignment = Alignment.Center,
    ) { CdIcon(icon, tint, size = iconSize) }
}

/** Numbered step bubble for how-to lists. */
@Composable
fun StepNumber(n: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(26.dp).clip(CircleShape).background(Accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Text("$n", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

// -------------------------------------------------------------- dialogs --

/** Confirmation dialog in the app's sheet style; [danger] tints the confirm action. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkSheet,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, style = displayStyle(20)) },
        text = { Text(text, color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (danger) DangerText else Accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextDim, fontWeight = FontWeight.SemiBold) }
        },
    )
}
