package com.crossdrop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.i18n.tr
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.HairlineStrong
import com.crossdrop.ui.theme.InkSheet
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = HairlineStrong,
    focusedLabelColor = Accent,
    unfocusedLabelColor = TextDim,
    cursorColor = Accent,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
)

@Composable
fun DeviceNameDialog(current: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        containerColor = InkSheet,
        shape = RoundedCornerShape(28.dp),
        title = { Text(tr("Name others see"), style = displayStyle(20)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                singleLine = true, label = { Text(tr("Device name")) },
                shape = RoundedCornerShape(16.dp), colors = fieldColors(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) {
                Text(tr("Save"), color = Accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = { onDone(null) }) { Text(tr("Cancel"), color = TextDim, fontWeight = FontWeight.SemiBold) }
        },
    )
}

@Composable
fun AddPeerDialog(onDone: () -> Unit, onAdd: (host: String, port: Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = remember(text) {
        val h = text.trim().substringBefore(':')
        val p = text.trim().substringAfter(':', "0").toIntOrNull() ?: 0
        h.takeIf { it.isNotBlank() && p in 1..65535 }?.let { it to p }
    }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = InkSheet,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Add peer by address", style = displayStyle(20)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Places a peer on the radar as if discovery had found it. " +
                        "Useful when mDNS can't cross a network.",
                    color = TextSecondary, fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    singleLine = true, label = { Text("host:port — e.g. 10.0.2.17:43595") },
                    shape = RoundedCornerShape(16.dp), colors = fieldColors(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let { (h, p) -> onAdd(h, p) } },
                enabled = parsed != null,
            ) { Text(tr("Add"), color = Accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text(tr("Cancel"), color = TextDim, fontWeight = FontWeight.SemiBold) }
        },
    )
}

/** Type or paste text/a link to send; starts with whatever is on the clipboard. */
@Composable
fun SendTextDialog(initial: String, target: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        containerColor = InkSheet,
        shape = RoundedCornerShape(28.dp),
        title = { Text(tr("Send text to {0}", target), style = displayStyle(20), maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    minLines = 3, maxLines = 8, label = { Text(tr("Text or link")) },
                    shape = RoundedCornerShape(16.dp), colors = fieldColors(),
                )
                Text(
                    tr("They can copy it or open the link with one tap."),
                    color = TextDim, fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) {
                Text(tr("Send"), color = Accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = { onDone(null) }) { Text(tr("Cancel"), color = TextDim, fontWeight = FontWeight.SemiBold) }
        },
    )
}
