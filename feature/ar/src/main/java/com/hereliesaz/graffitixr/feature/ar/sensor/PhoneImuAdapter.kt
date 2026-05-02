package com.hereliesaz.graffitixr.feature.ar.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.hereliesaz.graffitixr.common.sensor.ImuSample
import com.hereliesaz.graffitixr.common.sensor.PhoneSensorSource
import com.hereliesaz.graffitixr.common.sensor.Vec3
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class PhoneImuAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val phoneSensorSource: PhoneSensorSource,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private var pendingGyro: Vec3? = null
    private var pendingAccel: Vec3? = null
    private var pendingTs: Long = 0L

    fun start() {
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() { sensorManager.unregisterListener(this) }

    override fun onSensorChanged(event: SensorEvent) {
        val v = Vec3(event.values[0], event.values[1], event.values[2])
        pendingTs = event.timestamp
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> pendingGyro = v
            Sensor.TYPE_LINEAR_ACCELERATION -> pendingAccel = v
        }
        val g = pendingGyro; val a = pendingAccel
        if (g != null && a != null) {
            phoneSensorSource.pumpImu(ImuSample(g, a, pendingTs))
            pendingGyro = null; pendingAccel = null
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
