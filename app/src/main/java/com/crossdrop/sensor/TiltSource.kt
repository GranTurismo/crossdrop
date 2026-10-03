package com.crossdrop.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * Low-pass-filtered gravity vector → slosh input for the liquid orb.
 * `x`/`y` are normalized roughly to [-1, 1]; `energy` is recent jerk, used to
 * inject turbulence when the user physically moves the phone.
 */
class TiltSource(context: Context) {

    data class Tilt(val x: Float = 0f, val y: Float = 0f, val energy: Float = 0f)

    private val sensorManager = context.getSystemService<SensorManager>()
    private val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _tilt = MutableStateFlow(Tilt())
    val tilt: StateFlow<Tilt> = _tilt

    private val listener = object : SensorEventListener {
        private var fx = 0f; private var fy = 0f
        private var lastX = 0f; private var lastY = 0f; private var lastZ = 0f
        private var energy = 0f

        override fun onSensorChanged(e: SensorEvent) {
            val alpha = 0.12f
            fx += alpha * (e.values[0] - fx)
            fy += alpha * (e.values[1] - fy)

            val jerk = abs(e.values[0] - lastX) + abs(e.values[1] - lastY) + abs(e.values[2] - lastZ)
            lastX = e.values[0]; lastY = e.values[1]; lastZ = e.values[2]
            energy = energy * 0.92f + jerk * 0.08f

            _tilt.value = Tilt(
                x = (-fx / 9.81f).coerceIn(-1f, 1f),
                y = (fy / 9.81f).coerceIn(-1f, 1f),
                energy = (energy / 4f).coerceIn(0f, 1f),
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        accel?.let { sensorManager?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager?.unregisterListener(listener)
        _tilt.value = Tilt()
    }
}
