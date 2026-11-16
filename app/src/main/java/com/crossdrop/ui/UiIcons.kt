package com.crossdrop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Named icon composables kept from the pre-redesign set (the desktop screen
// copies call them). They now draw the [CdIcons] vectors.

@Composable
fun IconRadar(tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) =
    CdIcon(CdIcons.Radar, tint, modifier, size)

@Composable
fun IconTransfers(tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) =
    CdIcon(CdIcons.Transfers, tint, modifier, size)

@Composable
fun IconInbox(tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) =
    CdIcon(CdIcons.Inbox, tint, modifier, size)

@Composable
fun IconGear(tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) =
    CdIcon(CdIcons.Sliders, tint, modifier, size)

@Composable
fun IconArrow(tint: Color, up: Boolean, modifier: Modifier = Modifier, size: Dp = 16.dp) =
    CdIcon(if (up) CdIcons.ArrowUp else CdIcons.ArrowDown, tint, modifier, size)

@Composable
fun IconPhoto(tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) =
    CdIcon(CdIcons.Photo, tint, modifier, size)

@Composable
fun IconDoc(tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) =
    CdIcon(CdIcons.Doc, tint, modifier, size)

@Composable
fun IconQr(tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) =
    CdIcon(CdIcons.Qr, tint, modifier, size)

@Composable
fun IconVault(tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) =
    CdIcon(CdIcons.Drive, tint, modifier, size)

@Composable
fun IconClose(tint: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) =
    CdIcon(CdIcons.Close, tint, modifier, size)

@Composable
fun IconCheck(tint: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) =
    CdIcon(CdIcons.Check, tint, modifier, size)
