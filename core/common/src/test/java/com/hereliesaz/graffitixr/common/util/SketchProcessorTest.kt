package com.hereliesaz.graffitixr.common.util

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchProcessorTest {

    @Test
    fun `sketchEffect has penColor parameter`() {
        val methods = SketchProcessor::class.java.declaredMethods
        val sketchEffect = methods.firstOrNull { it.name == "sketchEffect" }
        assertNotNull("sketchEffect method should exist", sketchEffect)
        val paramNames = sketchEffect!!.parameters.map { it.type.simpleName }
        // Should have 3 parameters: Bitmap, Int (thickness), Int (penColor)
        assertTrue(
            "sketchEffect should accept penColor parameter (3 params), got: $paramNames",
            sketchEffect.parameterCount >= 3
        )
    }
}
