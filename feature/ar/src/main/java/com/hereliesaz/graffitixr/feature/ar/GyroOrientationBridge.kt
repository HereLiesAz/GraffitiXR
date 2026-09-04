// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/GyroOrientationBridge.kt
package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.hereliesaz.graffitixr.feature.ar.util.RotationDeltaMath
import timber.log.Timber

/**
 * Bridges [HomographyArTracker]'s [HomographyArTracker.HomographyPose] through a brief vision
 * dropout (a hand or spray can blocking the lens, a fast pan) using ONLY the gyroscope's
 * already-fused absolute orientation — not the accelerometer, and not a hand-rolled gyro
 * integrator.
 *
 * **Why `TYPE_GAME_ROTATION_VECTOR` and not raw `TYPE_GYROSCOPE`:** integrating raw angular
 * velocity into a quaternion ourselves would duplicate — with more risk of a subtle bug and no
 * per-device tuning — exactly what this sensor already is: Android's own gyro-only (no
 * magnetometer) sensor-fusion orientation estimate. It carries the same "no metric truth, only
 * internal consistency" character as [HomographyArTracker]'s own pose, which is the right
 * character for a SHORT bridge.
 *
 * **Why this never ESTIMATES new position:** see `HomographyTracker.h`'s and the broader design
 * discussion this was scoped from — bridging genuine camera displacement needs the accelerometer,
 * which means double integration (drift grows with time², not linearly) plus gravity subtraction
 * (circular with orientation) plus per-device bias calibration. None of that is here.
 *
 * That is a different claim from "translation is left unchanged," which was this class's original
 * (incorrect) behavior: a view matrix's translation column is the ROTATED camera position
 * (`t = -R·C`), so holding `t` fixed while rotating `R` moves the camera centre `C` even though
 * the phone's actual position hasn't changed — exactly the drift this bridge exists to prevent.
 * The fix, applied by [com.hereliesaz.graffitixr.feature.ar.BridgedHomographyTracker] using
 * [cameraRotationDelta], is to rotate translation by the SAME delta as rotation: `t' = ΔR · t`.
 * That is not a translation estimate — it is the algebraically correct way to carry an unmoved 3D
 * point (the camera centre) through a rotation this bridge already knows, with zero new
 * assumptions and zero accelerometer involvement.
 *
 * **The one piece that is asserted, not verified — read before shipping.** [bridgedRotation]'s
 * result depends on a fixed rotation `A` relating the phone's IMU body axes to the rear camera's
 * optical axes. Per the Android CDD, a device's default-orientation sensor frame has +Z pointing
 * out of the SCREEN face; the rear camera looks the opposite physical direction, so the shared
 * optical (Z) axis needs no flip — that part is a platform guarantee, not a guess. The remaining
 * freedom is exactly the quarter-turn `rotationDeg` already threads through this codebase's other
 * sensor-to-display conversions (see `com.hereliesaz.graffitixr.feature.ar.anchor.CaptureRotation`,
 * whose own doc notes its convention was ONE PERSON'S READING until `CaptureRotationTest` closed
 * the loop). This class applies that same convention to a rotation delta instead of a pixel, but
 * has no equivalent device-verified test yet — the sign has not been confirmed on hardware.
 * Verify by starting a bridge (or forcing one) and rotating the phone one way; the bridged overlay
 * should turn the same way it would under live vision tracking, not the opposite.
 */
class GyroOrientationBridge(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gameRotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    /** True when this device has a usable sensor at all — callers should skip bridging otherwise. */
    val isAvailable: Boolean get() = gameRotationSensor != null

    // Published as a whole new array per sample (never mutated in place) so a concurrent reader on
    // another thread always sees either the old or the new sample, never a half-written one —
    // same pattern DeviceAttitudeProvider already uses for exactly this reason.
    @Volatile private var latestQuaternion: FloatArray? = null
    @Volatile private var referenceQuaternion: FloatArray? = null
    @Volatile private var referenceAtElapsedMs: Long = 0L

    private var registered = false

    /**
     * Begin sampling. Safe to call repeatedly. `SENSOR_DELAY_GAME` (~20 ms): unlike
     * [DeviceAttitudeProvider]'s one-shot-per-capture use, this is read every tracked frame while
     * bridging, so it needs to actually keep up with the camera's frame rate.
     */
    fun start() {
        val sm = sensorManager ?: return
        val sensor = gameRotationSensor ?: return
        if (registered) return
        registered = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        if (!registered) Timber.w("GyroOrientationBridge: registerListener failed")
    }

    /** Stop sampling. Idempotent. Must be called when the fallback tracker stops, or this stays hot. */
    fun stop() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return
        val v = event.values
        val w = if (v.size >= 4) {
            v[3]
        } else {
            val t = 1f - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]
            if (t > 0f) kotlin.math.sqrt(t) else 0f
        }
        latestQuaternion = RotationDeltaMath.normalize(floatArrayOf(v[0], v[1], v[2], w))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Mark "now" as the reference orientation — call this the moment [HomographyArTracker.track]
     * produces a fresh confident lock, so a later bridge measures rotation SINCE that lock. A
     * no-op (bridging simply won't be available) if no sample has arrived yet.
     */
    fun markReference() {
        val q = latestQuaternion ?: return
        referenceQuaternion = q
        referenceAtElapsedMs = SystemClock.elapsedRealtime()
    }

    /** Clears the reference — a later [bridgedRotation] returns null until [markReference] again. */
    fun clearReference() {
        referenceQuaternion = null
    }

    /** Milliseconds since [markReference], or -1 if it was never called (or a sample never arrived). */
    fun msSinceReference(): Long {
        if (referenceQuaternion == null) return -1L
        return SystemClock.elapsedRealtime() - referenceAtElapsedMs
    }

    /**
     * How much the CAMERA has rotated (in camera-optical axes) since [markReference] — i.e. the
     * `ΔR` such that a pose held from that reference should become `ΔR · pose`. Null when there is
     * no reference (nothing was ever locked, or [clearReference] was called) or no live sensor
     * sample yet.
     *
     * This is the primitive the whole bridge is built from: applying it to a held rotation gives
     * [bridgedRotation]'s result, and applying it to a held translation is what keeps a bridged
     * pose's CAMERA CENTRE fixed in space during a pure pan/tilt (see
     * [com.hereliesaz.graffitixr.feature.ar.BridgedHomographyTracker], which does both) — still not
     * a translation *estimate* (that needs the accelerometer, per this class's doc), just the
     * correct way to carry a fixed 3D point through a known rotation.
     *
     * @param rotationDeg the same sensor-to-display quarter-turn (0/90/180/270) every other
     *   geometric conversion in this codebase uses — see this class's doc for what is and isn't
     *   verified about its sign here.
     */
    fun cameraRotationDelta(rotationDeg: Int): FloatArray? {
        val qNow = latestQuaternion ?: return null
        val qRef = referenceQuaternion ?: return null

        // Derivation (see class doc): camera_from_plane(now) = A * R(conj(qNow) * qRef) * A^T *
        // lastGoodRotation, where A is the fixed IMU-body-to-camera-optical alignment — the
        // CDD-guaranteed Z-axis identity composed with the display's quarter-turn.
        val qDeltaBody = RotationDeltaMath.multiplyQuaternions(RotationDeltaMath.conjugate(qNow), qRef)
        val deltaBody = RotationDeltaMath.toRotationMatrix3x3(RotationDeltaMath.normalize(qDeltaBody))
        val a = RotationDeltaMath.rotationAboutZ(rotationDeg)
        val aT = RotationDeltaMath.transposeMat3(a)
        return RotationDeltaMath.multiplyMat3(RotationDeltaMath.multiplyMat3(a, deltaBody), aT)
    }

    /**
     * The tracked camera-from-plane rotation, held through the vision dropout since
     * [markReference] — i.e. [lastGoodRotation] rotated by [cameraRotationDelta]. Null under the
     * same conditions as [cameraRotationDelta].
     *
     * @param lastGoodRotation the row-major 3x3 rotation block of the last confidently-tracked
     *   [HomographyArTracker.HomographyPose.viewMatrix] (its column-major upper-left 3x3,
     *   transposed to row-major — see [RotationDeltaMath]'s doc on the convention split).
     */
    fun bridgedRotation(lastGoodRotation: FloatArray, rotationDeg: Int): FloatArray? {
        val deltaCamera = cameraRotationDelta(rotationDeg) ?: return null
        return RotationDeltaMath.multiplyMat3(deltaCamera, lastGoodRotation)
    }
}
