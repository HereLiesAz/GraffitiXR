package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StandaloneWallHitTestTest {
    private val intrinsics = CameraIntrinsics(
        fx = 500f,
        fy = 500f,
        cx = 320f,
        cy = 240f,
        width = 640,
        height = 480,
    )

    // Camera one unit in front of canonical z=0, looking straight at the wall.
    private val view = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, -1f, 1f,
    )

    private val root = StandaloneWallRegion(
        centerX = 0f,
        centerY = 0f,
        width = 1f,
        height = 0.6f,
    )

    @Test
    fun centerPixel_hitsCanonicalWallOrigin() {
        val hit = StandaloneWallHitTest.hit(
            screenX = 320f,
            screenY = 240f,
            viewMatrix = view,
            intrinsics = intrinsics,
            frameWidth = 640,
            frameHeight = 480,
            coveredRegions = listOf(root),
            wallLocked = true,
        )

        assertNotNull(hit)
        requireNotNull(hit)
        assertEquals(0f, hit.x, 1e-6f)
        assertEquals(0f, hit.y, 1e-6f)
        assertEquals(0f, hit.z, 0f)
    }

    @Test
    fun exactPageBoundary_isAccepted() {
        // x = 0.5 at z=0 when camera is 1 unit away: u = cx + fx * 0.5.
        val hit = StandaloneWallHitTest.hit(
            screenX = 570f,
            screenY = 240f,
            viewMatrix = view,
            intrinsics = intrinsics,
            frameWidth = 640,
            frameHeight = 480,
            coveredRegions = listOf(root),
            wallLocked = true,
        )

        assertNotNull(hit)
        assertEquals(0.5f, requireNotNull(hit).x, 1e-5f)
    }

    @Test
    fun pointOutsideEveryRegisteredPage_isRejected() {
        val hit = StandaloneWallHitTest.hit(
            screenX = 620f,
            screenY = 240f,
            viewMatrix = view,
            intrinsics = intrinsics,
            frameWidth = 640,
            frameHeight = 480,
            coveredRegions = listOf(root),
            wallLocked = true,
        )

        assertNull(hit)
    }

    @Test
    fun grownAtlasPage_extendsValidHitAreaWithoutChangingFrame() {
        val grown = StandaloneWallRegion(
            centerX = 0.8f,
            centerY = 0f,
            width = 1f,
            height = 0.6f,
        )
        val hit = StandaloneWallHitTest.hit(
            screenX = 620f,
            screenY = 240f,
            viewMatrix = view,
            intrinsics = intrinsics,
            frameWidth = 640,
            frameHeight = 480,
            coveredRegions = listOf(root, grown),
            wallLocked = true,
        )

        assertNotNull(hit)
        assertEquals(0.6f, requireNotNull(hit).x, 1e-5f)
    }

    @Test
    fun unlockedWall_neverReturnsSyntheticHit() {
        assertNull(
            StandaloneWallHitTest.hit(
                screenX = 320f,
                screenY = 240f,
                viewMatrix = view,
                intrinsics = intrinsics,
                frameWidth = 640,
                frameHeight = 480,
                coveredRegions = listOf(root),
                wallLocked = false,
            ),
        )
    }
}
