package com.crossdrop.ui.language

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.i18n.I18n
import com.crossdrop.core.i18n.tr
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.CircleButton
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.IconTile
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Glass
import com.crossdrop.ui.theme.InkSheet
import com.crossdrop.ui.theme.Sky
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.displayStyle

/**
 * Makes every `tr()` inside a composable refresh by itself when the language changes: the translation runtime
 * reads this snapshot state on each lookup (so the composition subscribes) and bumps it on a change. Call once at
 * startup (Android `CrossDropApp`, desktop `main`). Platform-free: shared with the desktop app.
 */
object LanguageReactivity {
    private val version = mutableIntStateOf(0)

    fun install() {
        I18n.onRead = { version.intValue }
        I18n.onChanged = { version.intValue++ }
    }
}

/** Globe button that opens the language list: icon only, so it is recognisable in any language. */
@Composable
fun LanguageButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    CircleButton(CdIcons.Globe, tr("Change language"), onClick, modifier, size = 40.dp, iconSize = 20.dp)
}

/** The Settings row: "Language" with the current language in its own script. */
@Composable
fun LanguageRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val current = if (I18n.choice() == null) tr("Automatic") + " · " + I18n.nativeName(I18n.language())
    else I18n.nativeName(I18n.language())
    GlassCard(
        modifier.fillMaxWidth(), radius = 22.dp, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        onClick = onClick, onClickLabel = tr("Change language"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(CdIcons.Globe, Sky, size = 40.dp, radius = 13.dp, iconSize = 20.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("Language"), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(current, color = TextDim, fontSize = 13.sp)
            }
            CdIcon(CdIcons.ChevronRight, TextDim, size = 18.dp)
        }
    }
}

/**
 * The language list: "Automatic" (follow the phone) and every shipped language in its own script. Picking one
 * applies at once and closes the list.
 */
@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
    val chosen = I18n.choice()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkSheet,
        shape = RoundedCornerShape(28.dp),
        title = { Text(tr("Language"), style = displayStyle(20)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LanguageOption(tr("Automatic"), tr("Follows your phone's language"), chosen == null) { I18n.setChoice(null); onDismiss() }
                for ((code, name) in I18n.nativeNames) {
                    LanguageOption(name, null, chosen == code) { I18n.setChoice(code); onDismiss() }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel"), color = TextDim, fontWeight = FontWeight.SemiBold) } },
    )
}

@Composable
private fun LanguageOption(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) Accent.copy(alpha = 0.12f) else Glass.copy(alpha = 0f))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = if (selected) Accent else TextPrimary, fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (subtitle != null) Text(subtitle, color = TextDim, fontSize = 12.sp)
        }
        if (selected) CdIcon(CdIcons.Check, Accent, Modifier.size(18.dp), size = 18.dp)
    }
}
