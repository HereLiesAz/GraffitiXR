package com.hereliesaz.graffitixr.feature.ar

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A coarse "which way was the target?" prior, from the sensor attitude saved with the target.
 *
 * **What this can and cannot do.** It compares where the rear camera pointed when the target was
 * captured (`CaptureEnvironment.attitude.rotationVector`, a `TYPE_ROTATION_VECTOR` quaternion
 * `[x, y, z, w]`, body to East-North-Up) with where it points now, and says how far to turn and
 * tilt. It is a compass-and-gravity hint good to maybe 10-20 degrees outdoors, worse near steel. It
 * does NOT place the overlay and it feeds nothing into tracking: ARCore's world frame resets every
 * session, so poses saved at capture cannot be reused; only the compass/attitude survives.
 *
 * **Untrusted heading policy.** If either the saved or the live magnetometer accuracy is
 * `SENSOR_STATUS_UNRELIABLE` (0) or `SENSOR_STATUS_ACCURACY_LOW` (1), the azimuth is not believable,
 * and a wrong "turn right 90" is worse than no hint, so the result is [Unknown]. Elevation alone is
 * not offered: it needs no compass but on its own it says nothing about where the wall is. An
 * accuracy of -1 (not reported) is treated as trusted: absence of a figure is not evidence of a
 * bad one, and the common case of devices that never report it would otherwise never get a hint.
 */
sealed interface DoodleSearchHint {
    /** No usable saved attitude, live sample, or trustworthy heading. */
    data class Unknown(val reason: Reason) : DoodleSearchHint

    /** Looking roughly the same way as at capture (within [DoodleSearchHintMath.ALIGNED_DEG]). */
    data object Aligned : DoodleSearchHint

    /**
     * [turnDeg]: positive = turn right (clockwise from above), negative = left, rounded to 5.
     * [tiltDeg]: positive = tilt up, negative = down, rounded to 5. Either may be 0.
     */
    data class Adjust(val turnDeg: Int, val tiltDeg: Int) : DoodleSearchHint

    enum class Reason { NO_SAVED_ATTITUDE, NO_LIVE_ATTITUDE, UNTRUSTED_HEADING }
}

object DoodleSearchHintMath {
    const val ALIGNED_DEG = 20.0
    const val ROUND_TO_DEG = 5

    /** Below this horizontal component (about 15 degrees from vertical) azimuth is meaningless. */
    private const val MIN_HORIZONTAL = 0.26

    /** Android `SENSOR_STATUS_UNRELIABLE` / `SENSOR_STATUS_ACCURACY_LOW`. */
    private const val ACCURACY_LOW = 1

    fun isHeadingTrusted(magneticAccuracy: Int): Boolean =
        !(magneticAccuracy == 0 || magneticAccuracy == ACCURACY_LOW)

    /**
     * `[x, y, z, w]` from a `TYPE_ROTATION_VECTOR` event's values (`values[3]` is the scalar when
     * present, else reconstructed as `sqrt(1 - |v|^2)`). Null when fewer than 3 values.
     */
    fun quaternionFromSensorValues(values: FloatArray): List<Float>? {
        if (values.size < 3) return null
        val x = values[0]; val y = values[1]; val z = values[2]
        val w = if (values.size >= 4) values[3] else {
            val t = 1f - x * x - y * y - z * z
            if (t > 0f) sqrt(t) else 0f
        }
        return listOf(x, y, z, w)
    }

    /**
     * Rear-camera look direction (device -Z) in ENU `[east, north, up]` for body to ENU quaternion
     * `[x, y, z, w]`, or null if malformed. Independent of display rotation.
     */
    fun lookDirectionEnu(q: List<Float>): DoubleArray? {
        if (q.size != 4 || q.any { !it.isFinite() }) return null
        var x = q[0].toDouble(); var y = q[1].toDouble()
        var z = q[2].toDouble(); var w = q[3].toDouble()
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n < 0.5) return null
        x /= n; y /= n; z /= n; w /= n
        // Third column of the rotation matrix is device +Z in the world; look is its negation.
        return doubleArrayOf(
            -2 * (x * z + y * w),
            -2 * (y * z - x * w),
            -(1 - 2 * (x * x + y * y)),
        )
    }

    /** Signed difference [to] - [from] in degrees, wrapped to (-180, 180]. */
    fun wrapDeg(d: Double): Double {
        var r = d % 360.0
        if (r > 180.0) r -= 360.0
        if (r <= -180.0) r += 360.0
        return r
    }

    fun compute(
        saved: List<Float>?,
        savedMagneticAccuracy: Int,
        live: List<Float>?,
        liveMagneticAccuracy: Int,
    ): DoodleSearchHint {
        val s = saved?.let { lookDirectionEnu(it) }
            ?: return DoodleSearchHint.Unknown(DoodleSearchHint.Reason.NO_SAVED_ATTITUDE)
        val l = live?.let { lookDirectionEnu(it) }
            ?: return DoodleSearchHint.Unknown(DoodleSearchHint.Reason.NO_LIVE_ATTITUDE)
        if (!isHeadingTrusted(savedMagneticAccuracy) || !isHeadingTrusted(liveMagneticAccuracy)) {
            return DoodleSearchHint.Unknown(DoodleSearchHint.Reason.UNTRUSTED_HEADING)
        }
        val elS = Math.toDegrees(asin(s[2].coerceIn(-1.0, 1.0)))
        val elL = Math.toDegrees(asin(l[2].coerceIn(-1.0, 1.0)))
        val tilt = elS - elL
        val horizS = sqrt(s[0] * s[0] + s[1] * s[1])
        val horizL = sqrt(l[0] * l[0] + l[1] * l[1])
        val turn = if (horizS < MIN_HORIZONTAL || horizL < MIN_HORIZONTAL) {
            0.0 // Looking (nearly) straight up/down: azimuth undefined, give only the tilt.
        } else {
            wrapDeg(Math.toDegrees(atan2(s[0], s[1])) - Math.toDegrees(atan2(l[0], l[1])))
        }
        if (abs(turn) <= ALIGNED_DEG && abs(tilt) <= ALIGNED_DEG) return DoodleSearchHint.Aligned
        return DoodleSearchHint.Adjust(roundDeg(turn), roundDeg(tilt))
    }

    private fun roundDeg(d: Double): Int =
        ((d / ROUND_TO_DEG).roundToInt() * ROUND_TO_DEG).let { if (it == -0) 0 else it }
}

/** How the "looking for your target" status escalates with time spent unmatched. */
enum class DoodleSearchPhase { LOOKING, HINT, OFFER_RECAPTURE }

/**
 * Elapsed-time state machine for [DoodleSearchPhase]. Resets whenever [update] sees
 * `searching == false` (target locked or no anchor) or a different [doodleSearchKey] (project/target
 * changed). [clock] returns milliseconds and is injectable for tests.
 */
class DoodleSearchPhaseTracker(
    private val clock: () -> Long,
    private val hintAfterMs: Long = HINT_AFTER_MS,
    private val recaptureAfterMs: Long = RECAPTURE_AFTER_MS,
) {
    private var startedAt: Long? = null
    private var key: Any? = null

    fun update(searching: Boolean, doodleSearchKey: Any?): DoodleSearchPhase {
        if (!searching || doodleSearchKey != key) {
            startedAt = null
            key = doodleSearchKey
        }
        if (!searching) return DoodleSearchPhase.LOOKING
        val now = clock()
        val start = startedAt ?: now.also { startedAt = it }
        val elapsed = now - start
        return when {
            elapsed > recaptureAfterMs -> DoodleSearchPhase.OFFER_RECAPTURE
            elapsed >= hintAfterMs -> DoodleSearchPhase.HINT
            else -> DoodleSearchPhase.LOOKING
        }
    }

    companion object {
        const val HINT_AFTER_MS = 6_000L
        const val RECAPTURE_AFTER_MS = 20_000L
    }
}
