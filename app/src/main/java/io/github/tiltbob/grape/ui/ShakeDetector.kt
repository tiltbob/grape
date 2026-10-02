package io.github.tiltbob.grape.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Fires [onShake] after a burst of hard shakes (the "rage shake"). Six accelerometer
 * peaks above [THRESHOLD_G] within [WINDOW_MS] count; a gentle wobble, a bump or a
 * couple of swings do not. Only listens between [start] and [stop].
 */
class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {

    private val sensorManager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var peaks = 0
    private var firstPeakAtMs = 0L
    private var lastPeakAtMs = 0L
    private var firedAtMs = 0L

    /** False when the device has no accelerometer. */
    val available: Boolean get() = accelerometer != null

    fun start() {
        val sensor = accelerometer ?: return
        peaks = 0
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0] / SensorManager.GRAVITY_EARTH
        val y = event.values[1] / SensorManager.GRAVITY_EARTH
        val z = event.values[2] / SensorManager.GRAVITY_EARTH
        val g = sqrt(x * x + y * y + z * z)
        if (g < THRESHOLD_G) return
        val now = System.currentTimeMillis()
        // One peak per swing: the sensor reports many samples above the threshold per shake.
        if (now - lastPeakAtMs < DEBOUNCE_MS) return
        lastPeakAtMs = now
        if (now - firstPeakAtMs > WINDOW_MS) {
            peaks = 0
            firstPeakAtMs = now
        }
        peaks++
        if (peaks >= PEAKS_NEEDED && now - firedAtMs > COOLDOWN_MS) {
            peaks = 0
            firedAtMs = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val THRESHOLD_G = 2.7f
        const val PEAKS_NEEDED = 6
        const val WINDOW_MS = 3000L
        const val DEBOUNCE_MS = 120L
        const val COOLDOWN_MS = 2000L
    }
}
