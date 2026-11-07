package com.crossdrop.sensor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop has no accelerometer, so the orb simply sits level; turbulence
 * still follows throughput. Same shape as the Android class so [LiquidOrb]
 * is shared unchanged.
 */
class TiltSource {
    data class Tilt(val x: Float = 0f, val y: Float = 0f, val energy: Float = 0f)

    private val _tilt = MutableStateFlow(Tilt(0f, 1f, 0f))
    val tilt: StateFlow<Tilt> = _tilt

    fun start() = Unit
    fun stop() = Unit
}
