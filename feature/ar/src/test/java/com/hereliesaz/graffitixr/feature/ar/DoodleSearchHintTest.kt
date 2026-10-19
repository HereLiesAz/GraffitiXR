package com.hereliesaz.graffitixr.feature.ar

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoodleSearchHintTest {

    /** Quaternion [x,y,z,w] for rotation of [deg] about unit axis. */
    private fun q(deg: Double, ax: Double, ay: Double, az: Double): List<Float> {
        val h = Math.toRadians(deg) / 2
        return listOf((ax * sin(h)).toFloat(), (ay * sin(h)).toFloat(), (az * sin(h)).toFloat(), cos(h).toFloat())
    }

    /**
     * Phone upright, camera looking at [headingDeg] (0 = north, 90 = east) with [elevDeg] up.
     * Identity body->ENU = phone flat, screen up, top toward... camera looks down (-Z world = -Up).
     * Build: pitch the camera up to horizon (rotate +90 about East x), then tilt up by elev, then
     * yaw clockwise by heading (negative rotation about Up).
     */
    private fun looking(headingDeg: Double, elevDeg: Double): List<Float> {
        // Rotation about East by (90 + elev): look (0,0,-1) -> (0, cos(elev), sin(elev)) north-up.
        val pitch = q(90 + elevDeg, 1.0, 0.0, 0.0)
        val yaw = q(-headingDeg, 0.0, 0.0, 1.0)
        return mul(yaw, pitch)
    }

    private fun mul(a: List<Float>, b: List<Float>): List<Float> {
        val (ax, ay, az, aw) = a; val (bx, by, bz, bw) = b
        return listOf(
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz,
        )
    }

    private fun assertDir(v: DoubleArray?, e: Double, n: Double, u: Double) {
        assertNotNull(v)
        assertEquals(e, v!![0], 1e-4); assertEquals(n, v[1], 1e-4); assertEquals(u, v[2], 1e-4)
    }

    @Test fun identityLooksDown() = assertDir(DoodleSearchHintMath.lookDirectionEnu(listOf(0f, 0f, 0f, 1f)), 0.0, 0.0, -1.0)

    @Test fun facingNorth() = assertDir(DoodleSearchHintMath.lookDirectionEnu(looking(0.0, 0.0)), 0.0, 1.0, 0.0)

    @Test fun facingEast() = assertDir(DoodleSearchHintMath.lookDirectionEnu(looking(90.0, 0.0)), 1.0, 0.0, 0.0)

    @Test fun facingUp() = assertDir(DoodleSearchHintMath.lookDirectionEnu(looking(0.0, 90.0)), 0.0, 0.0, 1.0)

    @Test fun sensorValuesToQuaternion() {
        assertEquals(listOf(0f, 0f, 0.6f, 0.8f), DoodleSearchHintMath.quaternionFromSensorValues(floatArrayOf(0f, 0f, 0.6f, 0.8f)))
        val three = DoodleSearchHintMath.quaternionFromSensorValues(floatArrayOf(0f, 0f, 0.6f))!!
        assertEquals(0.8f, three[3], 1e-6f)
        assertNull(DoodleSearchHintMath.quaternionFromSensorValues(floatArrayOf(0f, 0f)))
    }

    @Test fun malformedQuaternions() {
        assertNull(DoodleSearchHintMath.lookDirectionEnu(emptyList()))
        assertNull(DoodleSearchHintMath.lookDirectionEnu(listOf(0f, 0f, 0f)))
        assertNull(DoodleSearchHintMath.lookDirectionEnu(listOf(0f, 0f, 0f, 0f)))
        assertNull(DoodleSearchHintMath.lookDirectionEnu(listOf(Float.NaN, 0f, 0f, 1f)))
    }

    private fun hint(saved: List<Float>?, live: List<Float>?, sAcc: Int = 3, lAcc: Int = 3) =
        DoodleSearchHintMath.compute(saved, sAcc, live, lAcc)

    @Test fun savedEastLiveNorthTurnRight() =
        assertEquals(DoodleSearchHint.Adjust(90, 0), hint(looking(90.0, 0.0), looking(0.0, 0.0)))

    @Test fun savedNorthLiveEastTurnLeft() =
        assertEquals(DoodleSearchHint.Adjust(-90, 0), hint(looking(0.0, 0.0), looking(90.0, 0.0)))

    @Test fun turnAndTiltRoundedToFive() =
        assertEquals(DoodleSearchHint.Adjust(-40, 30), hint(looking(100.0, 30.0), looking(140.0, 0.0)))

    @Test fun tiltDown() =
        assertEquals(DoodleSearchHint.Adjust(0, -30), hint(looking(0.0, 0.0), looking(0.0, 30.0)))

    @Test fun wraparoundShortestWay() {
        assertEquals(DoodleSearchHint.Adjust(40, 0), hint(looking(10.0, 0.0), looking(330.0, 0.0)))
        assertEquals(DoodleSearchHint.Adjust(-40, 0), hint(looking(330.0, 0.0), looking(10.0, 0.0)))
        val opposite = hint(looking(180.0, 0.0), looking(0.0, 0.0)) as DoodleSearchHint.Adjust
        assertEquals(180, kotlin.math.abs(opposite.turnDeg))
    }

    @Test fun wrapDegRange() {
        assertEquals(-170.0, DoodleSearchHintMath.wrapDeg(190.0), 1e-9)
        assertEquals(170.0, DoodleSearchHintMath.wrapDeg(-190.0), 1e-9)
        assertEquals(180.0, DoodleSearchHintMath.wrapDeg(-180.0), 1e-9)
        assertEquals(0.0, DoodleSearchHintMath.wrapDeg(720.0), 1e-9)
    }

    @Test fun alignedThresholds() {
        assertEquals(DoodleSearchHint.Aligned, hint(looking(0.0, 0.0), looking(0.0, 0.0)))
        assertEquals(DoodleSearchHint.Aligned, hint(looking(0.0, 0.0), looking(19.0, 19.0)))
        assertTrue(hint(looking(0.0, 0.0), looking(25.0, 0.0)) is DoodleSearchHint.Adjust)
        assertTrue(hint(looking(0.0, 0.0), looking(0.0, 25.0)) is DoodleSearchHint.Adjust)
    }

    @Test fun lookingStraightUpGivesTiltOnly() =
        assertEquals(DoodleSearchHint.Adjust(0, 90), hint(looking(0.0, 90.0), looking(0.0, 0.0)))

    @Test fun unknownCases() {
        val ok = looking(0.0, 0.0)
        assertEquals(DoodleSearchHint.Unknown(DoodleSearchHint.Reason.NO_SAVED_ATTITUDE), hint(null, ok))
        assertEquals(DoodleSearchHint.Unknown(DoodleSearchHint.Reason.NO_SAVED_ATTITUDE), hint(emptyList(), ok))
        assertEquals(DoodleSearchHint.Unknown(DoodleSearchHint.Reason.NO_LIVE_ATTITUDE), hint(ok, null))
        assertEquals(DoodleSearchHint.Unknown(DoodleSearchHint.Reason.UNTRUSTED_HEADING), hint(ok, ok, sAcc = 0))
        assertEquals(DoodleSearchHint.Unknown(DoodleSearchHint.Reason.UNTRUSTED_HEADING), hint(ok, ok, sAcc = 1))
        assertEquals(DoodleSearchHint.Unknown(DoodleSearchHint.Reason.UNTRUSTED_HEADING), hint(ok, ok, lAcc = 1))
    }

    @Test fun unreportedAccuracyIsTrusted() {
        val ok = looking(0.0, 0.0)
        assertEquals(DoodleSearchHint.Aligned, hint(ok, ok, sAcc = -1, lAcc = -1))
        assertEquals(DoodleSearchHint.Aligned, hint(ok, ok, sAcc = 2, lAcc = 3))
    }

    // ---- phase ----

    @Test fun phaseTiming() {
        var t = 1_000L
        val tr = DoodleSearchPhaseTracker({ t })
        assertEquals(DoodleSearchPhase.LOOKING, tr.update(true, "p"))
        t += 5_999; assertEquals(DoodleSearchPhase.LOOKING, tr.update(true, "p"))
        t += 1; assertEquals(DoodleSearchPhase.HINT, tr.update(true, "p"))
        t = 1_000 + 20_000; assertEquals(DoodleSearchPhase.HINT, tr.update(true, "p"))
        t += 1; assertEquals(DoodleSearchPhase.OFFER_RECAPTURE, tr.update(true, "p"))
    }

    @Test fun phaseResetsOnLockAndOnTargetChange() {
        var t = 0L
        val tr = DoodleSearchPhaseTracker({ t })
        tr.update(true, "p"); t = 25_000
        assertEquals(DoodleSearchPhase.OFFER_RECAPTURE, tr.update(true, "p"))
        assertEquals(DoodleSearchPhase.LOOKING, tr.update(false, "p")) // locked
        t = 26_000
        assertEquals(DoodleSearchPhase.LOOKING, tr.update(true, "p")) // restarts
        t = 47_000
        assertEquals(DoodleSearchPhase.OFFER_RECAPTURE, tr.update(true, "p"))
        assertEquals(DoodleSearchPhase.LOOKING, tr.update(true, "other")) // target changed
        t = 54_000
        assertEquals(DoodleSearchPhase.HINT, tr.update(true, "other"))
    }
}
