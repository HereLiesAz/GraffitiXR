package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization of the central tension in the teleological loop, found by the audit.
 *
 * The target-creation flow tells the artist to tap their painted reference marks, and the fingerprint
 * is masked to those marks. The artist then places the design over them. `FingerprintPartition`
 * tags every mark under the design INSIDE, and relocalization only matches OUTSIDE marks
 * (`MobileGS.cpp`: a non-OUTSIDE wall correspondence is skipped). So marks the artist puts where the
 * mural goes cannot be used to relocalize once the design is placed.
 *
 * Pinned as it is today. SHOULD BECOME: the backbone keeps unmasked capture features outside the
 * design's footprint (the tapped marks are only for anchoring and centring), so this count stays
 * above [FingerprintPartition.MIN_BACKBONE].
 */
class FingerprintPartitionWeaknessTest {

    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    @Test fun `marks tapped under the design leave no backbone to relocalize against`() {
        // 200 reference marks within 0.3 m of the centre of a 2 m x 1.5 m design placed over them.
        val pts = FloatArray(200 * 3)
        for (i in 0 until 200) {
            pts[i * 3] = ((i % 20) / 19f - 0.5f) * 0.6f
            pts[i * 3 + 1] = ((i / 20) / 9f - 0.5f) * 0.6f
            pts[i * 3 + 2] = 0f
        }
        val regions = FingerprintPartition.classify(pts, identity, halfW = 1.0f, halfH = 0.75f)
        val backbone = FingerprintPartition.backboneCount(regions)
        assertEquals("every tapped mark lies under the design", 0, backbone)
        assertTrue(backbone < FingerprintPartition.MIN_BACKBONE)
    }
}
