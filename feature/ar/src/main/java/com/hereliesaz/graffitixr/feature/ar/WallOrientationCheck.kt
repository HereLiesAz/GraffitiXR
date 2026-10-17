package com.hereliesaz.graffitixr.feature.ar

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Does a relocalization solve land on a wall that is oriented the way the SAVED wall was?
 *
 * Marks alone can match the wrong wall (similar texture, a repeated pattern). The saved target
 * carries the wall's normal in East-North-Up (`SlamManager.getWallNormalEnu`), learned at its locks.
 * ARCore's world is gravity-aligned (+Y up) but its yaw is arbitrary per session, so:
 *  - with a live compass attitude, the saved normal is carried into this session's world and the two
 *    normals must agree as LINES (a wall faces either way) within [TURN_TOL_DEG];
 *  - without one, only the wall's tilt from vertical can be compared ([TILT_TOL_DEG]), which still
 *    rejects a floor, ceiling or slanted surface standing in for the wall.
 * A missing saved normal, or a solved normal that cannot be trusted, is [Verdict.UNKNOWN]: pass.
 */
internal object WallOrientationCheck {

    enum class Verdict { UNKNOWN, OK, TILT_MISMATCH, TURN_MISMATCH }

    const val TILT_TOL_DEG = 25f
    const val TURN_TOL_DEG = 40f

    /**
     * @param savedNormalEnu the saved wall normal, East-North-Up, or null.
     * @param solvedNormalWorld the solved wall's normal in this session's world (+Y up).
     * @param worldFromEarth row-major 3x3 mapping ENU to this session's world, or null when no live
     *   attitude is available.
     */
    fun check(
        savedNormalEnu: FloatArray?,
        solvedNormalWorld: FloatArray,
        worldFromEarth: FloatArray?,
    ): Verdict {
        val saved = unit(savedNormalEnu) ?: return Verdict.UNKNOWN
        val solved = unit(solvedNormalWorld) ?: return Verdict.UNKNOWN
        if (worldFromEarth != null && worldFromEarth.size == 9) {
            val p = unit(
                floatArrayOf(
                    worldFromEarth[0] * saved[0] + worldFromEarth[1] * saved[1] + worldFromEarth[2] * saved[2],
                    worldFromEarth[3] * saved[0] + worldFromEarth[4] * saved[1] + worldFromEarth[5] * saved[2],
                    worldFromEarth[6] * saved[0] + worldFromEarth[7] * saved[1] + worldFromEarth[8] * saved[2],
                ),
            ) ?: return Verdict.UNKNOWN
            val dot = abs(p[0] * solved[0] + p[1] * solved[1] + p[2] * solved[2]).coerceAtMost(1f)
            val deg = Math.toDegrees(acos(dot.toDouble())).toFloat()
            return if (deg <= TURN_TOL_DEG) Verdict.OK else Verdict.TURN_MISMATCH
        }
        // Tilt from vertical: asin of the normal's vertical component (ENU up = z, world up = y).
        val savedTilt = Math.toDegrees(asin(abs(saved[2]).coerceAtMost(1f).toDouble())).toFloat()
        val solvedTilt = Math.toDegrees(asin(abs(solved[1]).coerceAtMost(1f).toDouble())).toFloat()
        return if (abs(savedTilt - solvedTilt) <= TILT_TOL_DEG) Verdict.OK else Verdict.TILT_MISMATCH
    }

    /**
     * Row-major 3x3 ENU to world from the frame's ARCore camera rotation (GL convention, row-major
     * [worldFromGlCamera]) and the attitude's earth to OpenCV camera [earthFromCv] (row-major).
     * worldFromEarth = worldFromCv * earthFromCvᵀ, with worldFromCv = worldFromGl * diag(1, -1, -1).
     */
    fun worldFromEarth(worldFromGlCamera: FloatArray, earthFromCv: FloatArray): FloatArray {
        require(worldFromGlCamera.size == 9 && earthFromCv.size == 9)
        val sign = floatArrayOf(1f, -1f, -1f)
        val worldFromCv = FloatArray(9) { i -> worldFromGlCamera[i] * sign[i % 3] }
        return FloatArray(9) { i ->
            val r = i / 3; val c = i % 3
            var sum = 0f
            for (k in 0..2) sum += worldFromCv[r * 3 + k] * earthFromCv[c * 3 + k]
            sum
        }
    }

    private fun unit(v: FloatArray?): FloatArray? {
        if (v == null || v.size < 3) return null
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (!(len > 1e-4f) || !len.isFinite()) return null
        return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }
}
