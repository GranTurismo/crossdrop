package com.crossdrop.shake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.crossdrop.core.shake.ShakeGesture

/**
 * The accelerometer, read only while [start]ed (the radar is on screen): every sample goes through
 * [ShakeGesture], and [onShake] runs on the main thread for each shake. Costs nothing while stopped.
 */
class ShakeSensor(context: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val sensors = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var gesture = ShakeGesture()
    private var running = false

    val available: Boolean get() = accelerometer != null

    fun start() {
        if (running || accelerometer == null) return
        gesture = ShakeGesture()
        running = sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        sensors.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (gesture.sample(event.timestamp, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())) onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
