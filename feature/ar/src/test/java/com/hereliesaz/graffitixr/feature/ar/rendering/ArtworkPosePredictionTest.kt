package com.hereliesaz.graffitixr.feature.ar.rendering

import com.hereliesaz.sphereslam.attitude.AttitudeExtrapolator
import com.hereliesaz.sphereslam.attitude.DisplayPosePredictor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ArtworkPosePredictionTest {

    private val identityView = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, -2f, 1f,
    )
    private val identity3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val identityQuat = floatArrayOf(0f, 0f, 0f, 1f)

    /** Body→world quaternion `[x, y, z, w]` for [deg] about the device z axis. */
    private fun aboutZ(deg: Float): FloatArray {
        val half = Math.toRadians(deg / 2.0)
        return floatArrayOf(0f, 0f, sin(half).toFloat(), cos(half).toFloat())
    }

    @Test
    fun `without a predictor or attitude the last vision pose is drawn unchanged`() {
        val last = identityView.copyOf()
        val scratch = FloatArray(16)
        assertSame(last, ArtworkPosePrediction.viewForDraw(null, AttitudeExtrapolator(), 0L, last, scratch))
        assertSame(last, ArtworkPosePrediction.viewForDraw(DisplayPosePredictor(), null, 0L, last, scratch))
    }

    @Test
    fun `an empty or cleared predictor falls back to the last vision pose`() {
        val predictor = DisplayPosePredictor()
        val attitude = AttitudeExtrapolator().apply { push(identityQuat, 1_000L) }
        val last = identityView.copyOf()
        val scratch = FloatArray(16)
        assertSame(last, ArtworkPosePrediction.viewForDraw(predictor, attitude, 1_000L, last, scratch))

        predictor.update(identityView, identityQuat, identity3, 1_000L)
        predictor.clear()
        assertSame(last, ArtworkPosePrediction.viewForDraw(predictor, attitude, 1_000L, last, scratch))
    }

    @Test
    fun `no attitude sample yet falls back to the last vision pose`() {
        val predictor = DisplayPosePredictor().apply { update(identityView, identityQuat, identity3, 0L) }
        val last = identityView.copyOf()
        assertSame(last, ArtworkPosePrediction.viewForDraw(predictor, AttitudeExtrapolator(), 5L, last, FloatArray(16)))
    }

    @Test
    fun `a held pose is re-posed by the attitude change since its frame`() {
        val predictor = DisplayPosePredictor().apply { update(identityView, identityQuat, identity3, 0L) }
        val attitude = AttitudeExtrapolator().apply {
            push(identityQuat, 0L)
            push(aboutZ(10f), 10_000_000L)
        }
        val last = identityView.copyOf()
        val scratch = FloatArray(16)

        // No attitude change at the frame's own time: the vision pose itself.
        val atFrame = ArtworkPosePrediction.viewForDraw(predictor, attitude, 0L, last, scratch)
        assertSame(scratch, atFrame)
        for (i in 0 until 16) assertEquals(identityView[i], atFrame[i], 1e-5f)

        // 10° device roll later: the view rotates by 10° about the camera axis, camera centre held.
        val later = ArtworkPosePrediction.viewForDraw(predictor, attitude, 10_000_000L, last, scratch).copyOf()
        assertNotEquals(identityView[0], later[0], 1e-4f)
        assertEquals(cos(Math.toRadians(10.0)).toFloat(), later[0], 1e-4f)
        assertEquals(1f, later[10], 1e-5f)
        // Camera centre (−Rᵀt) stays at the original (0, 0, 2).
        val cz = -(later[8] * later[12] + later[9] * later[13] + later[10] * later[14])
        assertEquals(2f, cz, 1e-4f)
        // The pushed vision pose itself is never mutated.
        for (i in 0 until 16) assertEquals(identityView[i], last[i], 0f)
    }
}
