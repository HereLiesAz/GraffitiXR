package com.hereliesaz.graffitixr.feature.ar.rendering

import android.graphics.Bitmap
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * JVM-reachable pieces of [HomographyOverlayRenderer]: its UI→GL texture mailbox. The on-screen
 * projection it draws with (the preview's `FILL_CENTER` crop, which replaced the old letterboxed
 * viewport) is pure math in SphereSLAM's `camera.CameraFillCenter`, tested in that library;
 * `onDrawFrame` itself needs a live EGL context.
 */
class HomographyOverlayRendererTest {

    @Test
    fun `texture mailbox clear wins when it is the latest command`() {
        val mailbox = TextureUpdateMailbox()
        val bitmap = mockk<Bitmap>(relaxed = true)

        mailbox.replace(bitmap)
        mailbox.clear()

        assertSame(TextureUpdateCommand.Clear, mailbox.take())
        assertNull(mailbox.take())
    }

    @Test
    fun `texture mailbox replacement wins when it is the latest command`() {
        val mailbox = TextureUpdateMailbox()
        val bitmap = mockk<Bitmap>(relaxed = true)

        mailbox.clear()
        mailbox.replace(bitmap)

        val command = mailbox.take() as TextureUpdateCommand.Replace
        assertSame(bitmap, command.bitmap)
        assertNull(mailbox.take())
    }
}
