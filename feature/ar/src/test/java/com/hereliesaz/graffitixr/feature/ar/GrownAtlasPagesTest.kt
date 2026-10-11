package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GrownAtlasPagesTest {

    private fun page(no: Int, width: Float = 1f) = SphereSlamStandaloneAtlasReferenceImage(
        pageNo = no,
        luma = ByteArray(4),
        width = 2,
        height = 2,
        referenceWidthMeters = width,
        physicallyMetric = false,
        canonicalFromPage = FloatArray(16) { if (it % 5 == 0) 1f else 0f },
    )

    @Test
    fun `no grown pages returns the persisted list unchanged`() {
        val persisted = listOf(page(1))
        assertSame(persisted, withGrownAtlasPages(persisted, emptyList()))
    }

    @Test
    fun `grown pages are appended so a re-sent page set keeps them`() {
        val merged = withGrownAtlasPages(listOf(page(1)), listOf(page(2), page(3)))
        assertEquals(listOf(1, 2, 3), merged.map { it.pageNo })
    }

    @Test
    fun `a persisted page wins a page-number clash and grown duplicates collapse`() {
        val merged = withGrownAtlasPages(listOf(page(2, width = 5f)), listOf(page(2), page(4), page(4)))
        assertEquals(listOf(2, 4), merged.map { it.pageNo })
        assertEquals(5f, merged.first().referenceWidthMeters, 0f)
    }
}
