package com.crossdrop.ui.contacts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.i18n.trPlural
import com.crossdrop.switchphones.AndroidContacts
import com.crossdrop.ui.BackHeader
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.ContactInitials
import com.crossdrop.ui.FootNote
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.PillStyle
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.GlassMid
import com.crossdrop.ui.theme.HairlineStrong
import com.crossdrop.ui.theme.OnAccent
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.theme.monoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pick one or more people from the address book to send as contact cards. Reading the list needs
 * contacts access; nothing is read beyond what's shown here until "Send" is tapped.
 */
@Composable
fun ContactPickerScreen(
    target: String,
    onSend: (ids: List<Long>) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(canRead(context)) }
    var denials by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok || canRead(context)
        if (!granted) denials++
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.READ_CONTACTS) }
    // Back from system settings with access switched on.
    LifecycleResumeEffect(Unit) {
        granted = canRead(context)
        onPauseOrDispose { }
    }

    var all by remember { mutableStateOf<List<AndroidContacts.Entry>?>(null) }
    LaunchedEffect(granted) {
        if (granted) all = withContext(Dispatchers.IO) { runCatching { AndroidContacts.list(context) }.getOrDefault(emptyList()) }
    }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(listOf<Long>()) }
    val shown = remember(all, query) { filter(all.orEmpty(), query) }

    Box(Modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.12f, centerY = 120.dp, radius = 220.dp)
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 16.dp)) {
            BackHeader(
                tr("Send contacts"), onClose,
                subtitle = { Text(tr("to {0}", target), color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
            if (!granted) {
                Column(
                    Modifier.weight(1f).padding(top = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(tr("Pick who to send"), style = displayStyle(26, tracking = -0.03f))
                    Text(
                        tr("CrossDrop needs to see your contacts to list them here. The cards you pick go straight to the other device — nothing is uploaded anywhere."),
                        color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                    PillButton(
                        tr("Allow contacts access"), {
                            when {
                                canRead(context) -> granted = true
                                // Android stops asking after two refusals: the switch is in Settings now.
                                denials >= 2 -> context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                                else -> launcher.launch(Manifest.permission.READ_CONTACTS)
                            }
                        },
                        Modifier.fillMaxWidth(), icon = CdIcons.Contacts, glow = true,
                    )
                }
                return@Column
            }

            SearchField(query, { query = it }, Modifier.padding(top = 12.dp, bottom = 8.dp))
            val list = all
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    list == null -> Text(tr("Loading contacts…"), color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 24.dp))
                    list.isEmpty() -> FootNote(tr("There are no contacts on this phone yet."), Modifier.padding(top = 16.dp), icon = CdIcons.Contacts)
                    shown.isEmpty() -> Text(tr("No one matches “{0}”.", query), color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 24.dp))
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
                        items(shown, key = { it.id }) { e ->
                            val on = e.id in selected
                            ContactRow(e, on) { selected = if (on) selected - e.id else selected + e.id }
                        }
                    }
                }
            }
            val pickedCount = selected.size
            PillButton(
                if (pickedCount == 0) tr("Pick contacts") else trPlural(pickedCount, "Send 1 contact", "Send {n} contacts"),
                { onSend(selected) },
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                enabled = selected.isNotEmpty(), icon = CdIcons.ArrowUp, glow = true,
            )
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.fillMaxWidth().height(48.dp).clip(shape).background(GlassMid)
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CdIcon(CdIcons.Search, TextDim, size = 18.dp)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(tr("Search name, number or email"), color = TextDim, fontSize = 15.sp, maxLines = 1)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = tr("Clear")) { onChange("") },
                contentAlignment = Alignment.Center,
            ) { CdIcon(CdIcons.Close, TextDim, size = 14.dp) }
        }
    }
}

@Composable
private fun ContactRow(e: AndroidContacts.Entry, selected: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (selected) Accent.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(role = Role.Checkbox, onClickLabel = if (selected) tr("Remove") else tr("Add"), onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ContactInitials(e.name, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.detail.isNotEmpty()) Text(e.detail, style = monoStyle(12, TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .background(if (selected) Accent else Color.Transparent)
                .border(1.5.dp, if (selected) Accent else HairlineStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) CdIcon(CdIcons.Check, OnAccent, size = 14.dp) }
    }
}

private fun canRead(context: Context) =
    context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

/** Name contains the query; or, when it has digits, a number containing them in any formatting. */
private fun filter(all: List<AndroidContacts.Entry>, query: String): List<AndroidContacts.Entry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return all
    val digits = q.filter { it.isDigit() }
    return all.filter { e ->
        e.name.lowercase().contains(q) || e.detail.lowercase().contains(q) ||
            (digits.length >= 3 && e.detail.filter { it.isDigit() }.contains(digits))
    }
}
