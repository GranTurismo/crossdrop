package com.crossdrop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.net.IncomingOffer
import com.crossdrop.ui.theme.DangerSoft
import com.crossdrop.ui.theme.DeepSpace
import com.crossdrop.ui.theme.DeepSpaceSoft
import com.crossdrop.ui.theme.RippleTeal
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.transfer.formatBytes

/** Modal sheet for an inbound OFFER — the human approves or declines. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingOfferSheet(
    offer: IncomingOffer,
    onRespond: (accept: Boolean) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { onRespond(false) },
        containerColor = DeepSpaceSoft,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${offer.peerName} wants to share",
                color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            offer.offer.files.take(4).forEach { f ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        f.name, color = TextPrimary, fontSize = 14.sp,
                        modifier = Modifier.weight(1f), maxLines = 1,
                    )
                    Text(formatBytes(f.size), color = TextDim, fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
            }
            if (offer.offer.files.size > 4) {
                Text("+ ${offer.offer.files.size - 4} more", color = TextDim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { onRespond(false) }) {
                    Text("Decline", color = DangerSoft)
                }
                Button(
                    onClick = { onRespond(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = RippleTeal),
                ) { Text("Accept", color = DeepSpace) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
