package com.crossdrop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.net.IncomingOffer
import com.crossdrop.ui.theme.AuroraBrush
import com.crossdrop.ui.theme.DangerSoft
import com.crossdrop.ui.theme.DeepSpace
import com.crossdrop.ui.theme.DeepSpaceRaised
import com.crossdrop.ui.theme.DeepSpaceSoft
import com.crossdrop.ui.theme.GlassEdge
import com.crossdrop.ui.theme.Phosphor
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
    android.util.Log.i("IncomingOfferSheet", "composed for ${offer.offer.transferId}")
    ModalBottomSheet(
        onDismissRequest = { onRespond(false) },
        containerColor = DeepSpaceSoft,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // peer avatar + headline
            Surface(
                shape = CircleShape,
                color = DeepSpaceRaised,
                border = BorderStroke(1.5.dp, RippleTeal.copy(alpha = 0.6f)),
            ) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    Text(
                        offer.peerName.firstOrNull()?.uppercase() ?: "?",
                        color = Phosphor, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "${offer.peerName} wants to share",
                color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "encrypted channel · ${offer.offer.files.size} file${if (offer.offer.files.size == 1) "" else "s"} · ${formatBytes(offer.offer.files.sumOf { it.size })}",
                color = TextDim, fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))

            // file list card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = DeepSpaceRaised,
                border = BorderStroke(1.dp, GlassEdge),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    offer.offer.files.take(4).forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FileTypeTile(f.name, size = 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                f.name, color = TextPrimary, fontSize = 14.sp,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(formatBytes(f.size), color = TextDim, fontSize = 13.sp)
                        }
                    }
                    if (offer.offer.files.size > 4) {
                        Text(
                            "+ ${offer.offer.files.size - 4} more",
                            color = TextDim, fontSize = 12.sp,
                            modifier = Modifier.padding(start = 46.dp, top = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            // actions
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onRespond(false) },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, DangerSoft.copy(alpha = 0.5f)),
                ) { Text("Decline", color = DangerSoft) }
                Button(
                    onClick = { onRespond(true) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = RippleTeal),
                ) { Text("Accept", color = DeepSpace, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
