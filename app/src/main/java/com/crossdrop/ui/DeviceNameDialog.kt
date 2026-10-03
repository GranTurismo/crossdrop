package com.crossdrop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.ui.theme.RippleTeal
import com.crossdrop.ui.theme.TextDim

@Composable
fun DeviceNameDialog(current: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Device name") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                singleLine = true, label = { Text("Visible name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(text) }) { Text("Save", color = RippleTeal) }
        },
        dismissButton = {
            TextButton(onClick = { onDone(null) }) { Text("Cancel", color = TextDim) }
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
        title = { Text("Add peer by address") },
        text = {
            Column {
                Text(
                    "Places a peer on the radar as if discovery had found it. " +
                        "Useful when mDNS can't cross a network.",
                    color = TextDim, fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    singleLine = true, label = { Text("host:port — e.g. 10.0.2.17:43595") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let { (h, p) -> onAdd(h, p) } },
                enabled = parsed != null,
            ) { Text("Add", color = RippleTeal) }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text("Cancel", color = TextDim) }
        },
    )
}
