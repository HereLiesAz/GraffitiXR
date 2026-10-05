// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/CompassHeadingProvider.kt
package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.annotation.VisibleForTesting
import timber.log.Timber

/**
 * Absolute compass heading of the rear camera's optical axis, for the spherical-coverage sweep
 * (Phase 3, `docs/SPHERESLAM_SPHERE_MAP.md`).
 *
 * **Why a compass and not the gyro.** A wall is viewable only from the arc in front of it, and that
 * arc is fixed in the world — so coverage needs a world-anchored bearing, not the gyro's
 * reference-relative one. `TYPE_GAME_ROTATION_VECTOR` (what [GyroOrientationBridge] uses) omits the
 * magnetometer on purpose: drift-free but with no absolute north. This uses `TYPE_ROTATION_VECTOR`,
 * which fuses the magnetometer in, so the heading is absolute and does not wander over a long pivot
 * (design §9.2). The two coexist: the gyro drives the short vision-dropout bridge and the Phase-2
 * off-wall placement; this drives only the coverage arc.
 *
 * **What "heading" means here.** Not where the top of the phone points (what `getOrientation`'s
 * azimuth gives, and ill-defined when the phone is pitched up at a wall) but where the **camera
 * looks**: the device −Z axis rotated into world East-North-Up and projected to horizontal, as a
 * compass bearing (0 = north, clockwise). That is the quantity the wall's viewable arc is defined
 * in.
 *
 * Fails soft: no rotation-vector sensor ⇒ [isAvailable] false and [latestHeadingDegrees] null, and
 * the sweep falls back to unanchored (first-observation) coverage. All reads are a whole-value swap,
 * safe to read from the camera worker.
 */
class CompassHeadingProvider(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** True when this device has a magnetometer-fused rotation-vector sensor. */
    val isAvailable: Boolean get() = rotationSensor != null

    @Volatile private var latestHeadingDeg: Float? = null
    @Volatile private var reliable: Boolean = true
    private var registered = false

    /**
     * Latest camera-axis compass heading in degrees `[0, 360)`, or null when no sample has arrived
     * (or after [stop], or on a device without the sensor).
     */
    fun latestHeadingDegrees(): Float? = latestHeadingDeg

    /** False once the system reports the magnetometer unreliable (needs a figure-8 recalibration). */
    val isReliable: Boolean get() = reliable

    /** Begin sampling. Safe to call repeatedly. UI rate is plenty for the keyframe-cadence coverage. */
    fun start() {
        val sm = sensorManager ?: return
        val sensor = rotationSensor ?: return
        if (registered) return
        registered = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        if (!registered) Timber.w("CompassHeadingProvider: registerListener failed")
    }

    /** Stop sampling. Idempotent. Clears the last sample so a later [start] never reports stale data. */
    fun stop() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        registered = false
        latestHeadingDeg = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        headingDegFromRotationVector(event.values)?.let { latestHeadingDeg = it }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            reliable = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        }
    }

    /**
     * Compass bearing of the camera's optical axis from a `TYPE_ROTATION_VECTOR` sample, or null if
     * the rotation matrix can't be formed or the axis is near-vertical (looking straight up/down, no
     * meaningful heading). Factored out as the testable seam — [SensorEvent] can't be built in a JVM
     * unit test.
     *
     * `R` maps device coordinates to world East-North-Up. The rear camera looks along device −Z, so
     * its world direction is the negated third column of `R`; its horizontal bearing is
     * `atan2(east, north)`.
     */
    @VisibleForTesting
    internal fun headingDegFromRotationVector(values: FloatArray): Float? {
        if (values.isEmpty()) return null
        val r = FloatArray(9)
        // getRotationMatrixFromVector returns void and throws on a malformed vector; a quaternion with
        // an extra estimated-heading-accuracy element (size 5) must be truncated to 4 first.
        val v = if (values.size > 4) values.copyOf(4) else values
        try {
            SensorManager.getRotationMatrixFromVector(r, v)
        } catch (_: IllegalArgumentException) {
            return null
        }
        // World direction of device −Z (row-major 3x3: third column is indices 2,5,8).
        val east = -r[2]
        val north = -r[5]
        if (east * east + north * north < 1e-8f) return null // camera pointing near-vertical
        var deg = Math.toDegrees(kotlin.math.atan2(east.toDouble(), north.toDouble())).toFloat()
        deg = ((deg % 360f) + 360f) % 360f
        return deg
    }
}
