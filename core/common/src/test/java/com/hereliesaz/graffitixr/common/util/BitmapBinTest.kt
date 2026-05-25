package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BitmapBinTest {

    private fun bitmap(recycled: Boolean = false): Bitmap = mockk(relaxed = true) {
        every { isRecycled } returns recycled
    }

    @Test
    fun `safeRecycle recycles a live bitmap`() {
        val bmp = bitmap(recycled = false)
        bmp.safeRecycle()
        verify(exactly = 1) { bmp.recycle() }
    }

    @Test
    fun `safeRecycle is a no-op on an already-recycled bitmap`() {
        val bmp = bitmap(recycled = true)
        bmp.safeRecycle()
        verify(exactly = 0) { bmp.recycle() }
    }

    @Test
    fun `safeRecycle tolerates null`() {
        val bmp: Bitmap? = null
        bmp.safeRecycle() // must not throw
    }

    @Test
    fun `BitmapSlot recycles the displaced bitmap on swap`() {
        val first = bitmap()
        val second = bitmap()
        val slot = BitmapSlot()

        slot.set(first)
        slot.set(second)

        verify(exactly = 1) { first.recycle() }
        verify(exactly = 0) { second.recycle() }
        assertSame(second, slot.bitmap)
    }

    @Test
    fun `BitmapSlot does not recycle when the same reference is set again`() {
        val only = bitmap()
        val slot = BitmapSlot()

        slot.set(only)
        slot.set(only)

        verify(exactly = 0) { only.recycle() }
        assertSame(only, slot.bitmap)
    }

    @Test
    fun `BitmapSlot clear recycles and nulls the held bitmap`() {
        val bmp = bitmap()
        val slot = BitmapSlot()

        slot.set(bmp)
        slot.clear()

        verify(exactly = 1) { bmp.recycle() }
        assertNull(slot.bitmap)
    }
}
