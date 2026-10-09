package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridKpmPageTest {
    private val identity = listOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)

    @Test
    fun `consistent persisted fields rebuild a valid page`() {
        val page = HybridKpmPage.fromPersisted(ByteArray(12), 4, 3, 1.2f, identity)
        assertNotNull(page)
        assertTrue(page!!.isValid())
    }

    @Test
    fun `missing or inconsistent persisted fields are refused`() {
        assertNull(HybridKpmPage.fromPersisted(null, 4, 3, 1.2f, identity))
        assertNull(HybridKpmPage.fromPersisted(ByteArray(11), 4, 3, 1.2f, identity))   // size ≠ w·h
        assertNull(HybridKpmPage.fromPersisted(ByteArray(12), 4, 3, 0f, identity))     // no scale
        assertNull(HybridKpmPage.fromPersisted(ByteArray(12), 4, 3, Float.NaN, identity))
        assertNull(HybridKpmPage.fromPersisted(ByteArray(12), 4, 3, 1.2f, emptyList())) // legacy
        assertNull(HybridKpmPage.fromPersisted(ByteArray(12), 4, 3, 1.2f, identity.dropLast(1) + 0f))
    }

    @Test
    fun `non-finite relation is invalid`() {
        val bad = identity.toFloatArray().also { it[12] = Float.POSITIVE_INFINITY }
        assertFalse(HybridKpmPage(ByteArray(12), 4, 3, 1.2f, bad).isValid())
    }
}
