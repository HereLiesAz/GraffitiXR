package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.KeyPoint

/**
 * IMPLEMENTATION.md **0.7** — `Fingerprint.isLegacyFrame()`.
 *
 * A fingerprint saved before Phase 0 holds 3D points back-projected from display-rotated pixels and
 * intrinsics against a *sensor*-frame view (`PAPER.md` §8). The skew depends on a rotation that was
 * never written to the file, so the points cannot be repaired afterwards — the target has to be
 * re-captured. This predicate is what lets the loader tell that case apart from a good one.
 *
 * The two failure modes it has to avoid are opposite and equally bad:
 *
 *  - **Too eager** — refusing a fingerprint that is fine. Post-Phase-0 captures record a rotation,
 *    including `0` for landscape, and `0` is a real value that must not read as "missing".
 *  - **Too lax** — accepting a legacy one, which puts the app back in exactly the state Phase 0
 *    exists to end, silently, and makes it look like poor tracking.
 */
class FingerprintLegacyFrameTest {

    private fun fp(points: List<Float>, rotation: Int, rows: Int = points.size / 3) = Fingerprint(
        keypoints = List(rows) { KeyPoint(1f, 1f, 7f) },
        points3d = points,
        descriptorsData = ByteArray(rows * 32),
        descriptorsRows = rows,
        descriptorsCols = 32,
        descriptorsType = 0,
        captureRotationDeg = rotation,
    )

    private val onePoint = listOf(1f, 2f, 3f)

    @Test
    fun `metric points with no recorded rotation are legacy`() {
        assertTrue(fp(onePoint, rotation = -1).isLegacyFrame())
    }

    /**
     * The trap. Landscape capture is `rotationNeeded == 0` — the orientation that needed no
     * correction at all — and it is a perfectly good post-Phase-0 fingerprint. If `0` were treated
     * as "unset", every landscape capture would be refused and the artist would be told to
     * re-capture a target that was already correct.
     */
    @Test
    fun `a landscape capture records zero and is not legacy`() {
        assertFalse(fp(onePoint, rotation = 0).isLegacyFrame())
    }

    @Test
    fun `every recorded quadrant is accepted`() {
        for (deg in intArrayOf(0, 90, 180, 270)) {
            assertFalse("rotation $deg must not read as legacy", fp(onePoint, deg).isLegacyFrame())
        }
    }

    /**
     * A descriptors-only fingerprint has no 3D to be in the wrong frame, so it is not legacy in this
     * sense however old it is. Refusing it would break the depth path and every keypoint-only
     * project for no benefit — there is nothing to re-capture *for*.
     */
    @Test
    fun `a fingerprint with no 3D points is never legacy`() {
        val descriptorsOnly = Fingerprint(
            keypoints = emptyList(),
            points3d = emptyList(),
            descriptorsData = ByteArray(32),
            descriptorsRows = 1,
            descriptorsCols = 32,
            descriptorsType = 0,
            captureRotationDeg = -1,
        )
        assertFalse(descriptorsOnly.isLegacyFrame())
    }

    /** The default is the unknown sentinel, so a fingerprint built without the field is caught. */
    @Test
    fun `the default rotation is unknown, not zero`() {
        val defaulted = Fingerprint(
            keypoints = List(1) { KeyPoint(1f, 1f, 7f) },
            points3d = onePoint,
            descriptorsData = ByteArray(32),
            descriptorsRows = 1,
            descriptorsCols = 32,
            descriptorsType = 0,
        )
        assertEquals(-1, defaulted.captureRotationDeg)
        assertTrue(defaulted.isLegacyFrame())
    }

    /**
     * The JNI factory cannot carry a rotation — its descriptor is frozen — so anything it builds is
     * `-1` by construction. That is correct rather than a gap: the depth path never applies a
     * display rotation to its geometry, so there is no value it could honestly report.
     */
    @Test
    fun `the frozen native factory yields the unknown sentinel`() {
        val fromNative = Fingerprint.fromNative(
            keypoints = List(1) { KeyPoint(1f, 1f, 7f) },
            points3d = onePoint,
            descriptorsData = ByteArray(32),
            descriptorsRows = 1,
            descriptorsCols = 32,
            descriptorsType = 0,
            patchData = ByteArray(0),
            markCenterLocal = emptyList(),
        )
        assertEquals(-1, fromNative.captureRotationDeg)
    }
}
