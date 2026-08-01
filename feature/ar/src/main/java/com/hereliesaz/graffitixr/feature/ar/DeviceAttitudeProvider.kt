package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.hereliesaz.graffitixr.common.model.DeviceAttitude
import timber.log.Timber

/**
 * Samples the device's **physical** attitude — the thing the app has never tracked.
 *
 * Everything orientation-related in this codebase was derived from `display.rotation`, which is a
 * property of the *window*, not the device. With `screenOrientation="fullUser"` and auto-rotate off
 * the two can disagree indefinitely. This closes that gap so a capture records how the phone was
 * actually held, not merely how it was being drawn.
 *
 * **Design: cache continuously, snapshot instantly.** Capture happens on the GL thread inside a
 * frame callback, so [snapshot] must not block, allocate heavily, or wait for a sensor. Listeners
 * keep the latest value of each sensor in a plain field and [snapshot] copies them. The cost while
 * registered is a handful of float writes per sensor event at [SensorManager.SENSOR_DELAY_UI], and
 * nothing at all when unregistered.
 *
 * **Absent is not zero.** A device may lack a magnetometer, or report one that is unreliable indoors
 * and near rebar. Every field degrades to empty/`NaN`/-1 rather than a plausible-looking value, for
 * the reason this project has already relearned twice: a hardcoded zero reads as a measurement.
 */
class DeviceAttitudeProvider(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gameRotationSensor =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    // Latest sample per sensor. Written on the sensor thread, read on the GL thread at capture —
    // @Volatile on the array references gives the reader a consistent view of a whole sample,
    // because each listener publishes a NEW array rather than mutating the existing one. Mutating
    // in place would let the reader see a half-updated vector.
    @Volatile private var rotationVector: FloatArray? = null
    @Volatile private var rotationVectorAccuracyRad: Float = -1f
    @Volatile private var gameRotationVector: FloatArray? = null
    @Volatile private var gravity: FloatArray? = null
    @Volatile private var magneticField: FloatArray? = null
    @Volatile private var magneticAccuracy: Int = -1
    @Volatile private var newestSampleElapsedNs: Long = 0L

    private var registered = false

    /** True when the device has at least one sensor this can use. */
    val isAvailable: Boolean
        get() = rotationVectorSensor != null || gameRotationSensor != null ||
            gravitySensor != null || magnetometer != null

    /**
     * Begin sampling. Safe to call repeatedly; a second call is a no-op.
     *
     * `SENSOR_DELAY_UI` (~60 ms) rather than `SENSOR_DELAY_GAME` or `FASTEST`: the value is read
     * once per capture, not per frame, so a faster rate would buy nothing and cost battery on a
     * device the artist is holding up for an hour.
     */
    fun start() {
        val sm = sensorManager ?: return
        if (registered) return
        var any = false
        for (s in listOfNotNull(rotationVectorSensor, gameRotationSensor, gravitySensor, magnetometer)) {
            any = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI) || any
        }
        registered = any
        if (!any) Timber.w("DeviceAttitudeProvider: no motion sensors could be registered")
    }

    /** Stop sampling. Idempotent. Must be called when AR pauses, or the sensors stay hot. */
    fun stop() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Publish a copy, never the event's own array — SensorEvent.values is reused by the
        // framework between callbacks, so retaining it hands the reader a buffer that changes
        // underneath them.
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                rotationVector = event.values.copyOf()
                // values[4], when present, is the estimated heading accuracy in radians.
                rotationVectorAccuracyRad = if (event.values.size >= 5) event.values[4] else -1f
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> gameRotationVector = event.values.copyOf()
            Sensor.TYPE_GRAVITY -> gravity = event.values.copyOf()
            Sensor.TYPE_MAGNETIC_FIELD -> magneticField = event.values.copyOf()
            else -> return
        }
        newestSampleElapsedNs = SystemClock.elapsedRealtimeNanos()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) magneticAccuracy = accuracy
    }

    /**
     * A snapshot of the latest sample from every sensor, or null when nothing has been sampled.
     *
     * Null rather than an all-empty record: "no attitude was available" and "attitude was available
     * and happened to be zero" must not look the same to whoever reads the capture later.
     */
    fun snapshot(): DeviceAttitude? {
        val rv = rotationVector
        val grv = gameRotationVector
        val g = gravity
        val mag = magneticField
        if (rv == null && grv == null && g == null && mag == null) return null

        val euler = eulerFromRotationVector(rv)
        val newest = newestSampleElapsedNs
        return DeviceAttitude(
            rotationVector = rv?.let { quaternionOf(it) } ?: emptyList(),
            rotationVectorAccuracyRad = rotationVectorAccuracyRad,
            gameRotationVector = grv?.let { quaternionOf(it) } ?: emptyList(),
            gravity = g?.take(3) ?: emptyList(),
            magneticFieldUt = mag?.take(3) ?: emptyList(),
            magneticAccuracy = magneticAccuracy,
            azimuthDeg = euler[0],
            pitchDeg = euler[1],
            rollDeg = euler[2],
            sampleAgeMs = if (newest > 0L) {
                (SystemClock.elapsedRealtimeNanos() - newest) / 1_000_000L
            } else {
                -1L
            },
        )
    }

    private companion object {

        /**
         * Normalize a rotation-vector sample to a full `[x, y, z, w]` quaternion.
         *
         * The framework's rotation vector is the first three components only on many devices;
         * `values[3]` is the scalar when present, and must otherwise be reconstructed as
         * `sqrt(1 - |v|²)`. Getting this wrong yields a quaternion that is silently not unit-length,
         * which every downstream rotation then scales by.
         */
        fun quaternionOf(values: FloatArray): List<Float> {
            val x = values.getOrElse(0) { 0f }
            val y = values.getOrElse(1) { 0f }
            val z = values.getOrElse(2) { 0f }
            val w = if (values.size >= 4) {
                values[3]
            } else {
                val t = 1f - x * x - y * y - z * z
                if (t > 0f) kotlin.math.sqrt(t) else 0f
            }
            return listOf(x, y, z, w)
        }

        /**
         * Azimuth/pitch/roll in degrees from a rotation vector, via the framework's own
         * `getRotationMatrixFromVector` + `getOrientation`.
         *
         * Deliberately NOT hand-rolled from the quaternion: the framework's convention (which axis
         * is azimuth, the sign of pitch, the remapping for the device's natural orientation) is the
         * one every other Android consumer expects, and re-deriving it is a well-known way to ship a
         * heading that is correct only in portrait.
         */
        fun eulerFromRotationVector(values: FloatArray?): FloatArray {
            if (values == null) return floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
            return try {
                val r = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(r, values)
                val o = FloatArray(3)
                SensorManager.getOrientation(r, o)
                floatArrayOf(
                    Math.toDegrees(o[0].toDouble()).toFloat(),
                    Math.toDegrees(o[1].toDouble()).toFloat(),
                    Math.toDegrees(o[2].toDouble()).toFloat(),
                )
            } catch (e: IllegalArgumentException) {
                // getRotationMatrixFromVector throws on a malformed vector on some OEM builds.
                Timber.w(e, "rotation vector -> euler failed")
                floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
            }
        }
    }
}
