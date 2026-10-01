package com.hereliesaz.graffitixr.feature.ar

import android.net.Uri
import com.hereliesaz.graffitixr.common.model.CoopSpatialFrame
import com.hereliesaz.graffitixr.common.model.CoopSpatialScale
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.ModeAdjustment
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoopSpectatorProjectNormalizerTest {
    private val pageUri = Uri.parse("file:///spectator/page.png")
    private val designUri = Uri.parse("file:///spectator/design.png")

    @Test
    fun `standalone host placement becomes ARCore placement with matching metric base width`() {
        val standaloneAdjustment = ModeAdjustment(
            offsetX = 0.35f,
            offsetY = -0.2f,
            scale = 1.4f,
            rotation = 17f,
            opacity = 0.7f,
        )
        val staleArCoreAdjustment = ModeAdjustment(offsetX = 9f, scale = 3f)
        val project = GraffitiProject(
            sphereSlamReferenceUri = pageUri,
            sphereSlamReferenceWidthMeters = 4f,
            sphereSlamReferencePhysicallyMetric = true,
            sphereSlamModeAdjustment = standaloneAdjustment,
            modeAdjustments = mapOf(EditorMode.AR.name to staleArCoreAdjustment),
            design = OverlayLayer(uri = designUri),
            arDesignHalfWidthM = 7f,
        )
        val frame = spatial(
            host = CoopTrackingBackend.SPHERESLAM,
            scale = CoopSpatialScale.METRIC,
            width = 4f,
        )

        val normalized = normalizeCoopSpectatorProject(
            project = project,
            spatialFrame = frame,
            guestBackend = CoopTrackingBackend.ARCORE,
            imageDimensions = { uri ->
                when (uri) {
                    pageUri -> 1000 to 1000
                    designUri -> 2000 to 1000
                    else -> null
                }
            },
        )

        assertEquals(standaloneAdjustment, normalized.modeAdjustments[EditorMode.AR.name])
        // 4m square page + 2:1 design => 4m x 2m fitted base quad => half-width 2m.
        assertEquals(2f, normalized.arDesignHalfWidthM, 1e-6f)
        // The spectator normalization must not rewrite the host's source slot.
        assertEquals(standaloneAdjustment, normalized.sphereSlamModeAdjustment)
    }

    @Test
    fun `ARCore host placement becomes standalone placement and stale page metadata is removed`() {
        val arCoreAdjustment = ModeAdjustment(
            offsetX = -0.4f,
            offsetY = 0.25f,
            scale = 0.8f,
            rotation = -12f,
            brightness = 0.1f,
        )
        val staleStandaloneAdjustment = ModeAdjustment(offsetX = 5f, scale = 2f)
        val project = GraffitiProject(
            sphereSlamReferenceUri = pageUri,
            sphereSlamReferenceWidthMeters = 3f,
            sphereSlamReferencePhysicallyMetric = true,
            sphereSlamAnchorGeneration = 8L,
            sphereSlamPlacementAnchorGeneration = 8L,
            sphereSlamModeAdjustment = staleStandaloneAdjustment,
            modeAdjustments = mapOf(EditorMode.AR.name to arCoreAdjustment),
            design = OverlayLayer(uri = designUri),
        )
        val frame = spatial(
            host = CoopTrackingBackend.ARCORE,
            scale = CoopSpatialScale.METRIC,
            width = 2f,
        )

        val normalized = normalizeCoopSpectatorProject(
            project = project,
            spatialFrame = frame,
            guestBackend = CoopTrackingBackend.SPHERESLAM,
            imageDimensions = { null },
        )

        assertEquals(arCoreAdjustment, normalized.sphereSlamModeAdjustment)
        assertNull(normalized.sphereSlamReferenceUri)
        assertEquals(0L, normalized.sphereSlamAnchorGeneration)
        assertEquals(0L, normalized.sphereSlamPlacementAnchorGeneration)
        assertEquals(arCoreAdjustment, normalized.modeAdjustments[EditorMode.AR.name])
    }

    @Test
    fun `same-backend spectator project is not rewritten`() {
        val project = GraffitiProject(
            sphereSlamReferenceUri = pageUri,
            sphereSlamModeAdjustment = ModeAdjustment(offsetX = 0.1f),
            design = OverlayLayer(uri = designUri),
        )
        val frame = spatial(
            host = CoopTrackingBackend.SPHERESLAM,
            scale = CoopSpatialScale.NORMALIZED_PAGE,
            width = 1f,
        )

        val normalized = normalizeCoopSpectatorProject(
            project,
            frame,
            CoopTrackingBackend.SPHERESLAM,
        ) { null }

        assertEquals(project, normalized)
    }

    @Test
    fun `standalone to ARCore refuses to invent metric base width when dimensions are missing`() {
        val project = GraffitiProject(
            sphereSlamReferenceUri = pageUri,
            sphereSlamReferenceWidthMeters = 2f,
            sphereSlamReferencePhysicallyMetric = true,
            sphereSlamModeAdjustment = ModeAdjustment(scale = 1.3f),
            design = OverlayLayer(uri = designUri),
            arDesignHalfWidthM = -1f,
        )

        val normalized = normalizeCoopSpectatorProject(
            project,
            spatial(CoopTrackingBackend.SPHERESLAM, CoopSpatialScale.METRIC, 2f),
            CoopTrackingBackend.ARCORE,
        ) { null }

        assertEquals(-1f, normalized.arDesignHalfWidthM, 0f)
    }

    private fun spatial(
        host: CoopTrackingBackend,
        scale: CoopSpatialScale,
        width: Float,
    ) = CoopSpatialFrame(
        hostBackend = host,
        fingerprintFrameVersion = 1,
        scale = scale,
        fingerprintAvailable = true,
        anchorRevision = 1L,
        referenceWidthUnits = width,
        fingerprintFromWall = listOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        ),
    )
}
