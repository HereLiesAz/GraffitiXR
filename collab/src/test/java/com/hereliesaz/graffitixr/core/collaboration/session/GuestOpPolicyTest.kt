package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.BrushStroke
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend.ARCORE
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend.SPHERESLAM
import com.hereliesaz.graffitixr.common.model.LayerProps
import com.hereliesaz.graffitixr.common.model.ModeAdjustment
import com.hereliesaz.graffitixr.common.model.Op
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestOpPolicyTest {
    @Test
    fun `AR placement crosses only between peers on the same backend`() {
        val ar = Op.ModeTransform("AR", ModeAdjustment(scale = 2f))
        assertTrue(GuestOpPolicy.allows(ar, ARCORE, ARCORE))
        assertFalse(GuestOpPolicy.allows(ar, ARCORE, SPHERESLAM))
        assertFalse(GuestOpPolicy.allows(ar, SPHERESLAM, ARCORE))
        assertFalse(GuestOpPolicy.allows(ar, null, ARCORE)) // host backend not known yet
    }

    @Test
    fun `screen-space modes and design state cross freely`() {
        assertTrue(GuestOpPolicy.allows(Op.ModeTransform("TRACE", ModeAdjustment()), ARCORE, SPHERESLAM))
        assertTrue(GuestOpPolicy.allows(Op.DesignProps(LayerProps(opacity = 0.5f)), ARCORE, SPHERESLAM))
        assertTrue(GuestOpPolicy.allows(Op.DesignTransform(List(16) { 0f }), ARCORE, ARCORE))
    }

    @Test
    fun `a guest's rendered pixels are not sent (the host persists source plus effect flags)`() {
        assertFalse(GuestOpPolicy.allows(Op.DesignBitmapReplace(byteArrayOf(1)), ARCORE, ARCORE))
    }

    @Test
    fun `a guest cannot replace the design`() {
        val layer = com.hereliesaz.graffitixr.common.model.Layer(id = "x", name = "x", uri = null)
        assertFalse(GuestOpPolicy.allows(Op.DesignReplace(layer), ARCORE, ARCORE))
    }

    @Test
    fun `authoring ops this app ignores are not sent`() {
        assertFalse(GuestOpPolicy.allows(Op.StrokeComplete(BrushStroke(points = listOf(1f))), ARCORE, ARCORE))
        assertFalse(GuestOpPolicy.allows(Op.TextContentChange("x"), ARCORE, ARCORE))
    }
}
