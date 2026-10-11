package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.graphics.Bitmap
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GLSurfaceView recreates the EGL context after onPause, and onSurfaceCreated then rebuilds every
 * sub-renderer. Names and "uploaded" flags from the dead context must not survive that rebuild.
 * (No GL in a JVM test, so this exercises the bookkeeping, which is exactly what was wrong.)
 */
class GlContextLossTest {

    private val context: Context = mockk(relaxed = true)

    @Test
    fun `overlay forgets its uploaded artwork when the GL context is recreated`() {
        val overlay = OverlayRenderer(context)
        overlay.updateTexture(mockk<Bitmap>(relaxed = true))
        assertTrue(overlay.hasTexture)

        // New EGL context (createOnGlThread runs this first): the old texture name holds nothing, so
        // drawing must wait for a re-upload (ArRenderer.onSurfaceCreated marks the retained bitmap
        // dirty) instead of drawing black.
        overlay.onGlContextLost()
        assertFalse(overlay.hasTexture)
    }

    @Test
    fun `perception FBO drops stale names so resize rebuilds it in the new context`() {
        val fbo = PerceptionFbo()
        setField(fbo, "fbo", 7)
        setField(fbo, "width", 1080)
        setField(fbo, "height", 2400)
        setField(fbo, "complete", true)
        assertTrue(fbo.isSized())

        fbo.onGlContextLost() // createOnGlThread's first step

        // With the stale name kept, resize(sameW, sameH) early-returned and the perception pass bound
        // a framebuffer that does not exist in the new context.
        assertFalse(fbo.isSized())
        assertTrue(getField(fbo, "fbo") == 0)
    }

    private fun setField(obj: Any, name: String, value: Any) {
        obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(obj, value)
    }

    private fun getField(obj: Any, name: String): Any? =
        obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)
}
