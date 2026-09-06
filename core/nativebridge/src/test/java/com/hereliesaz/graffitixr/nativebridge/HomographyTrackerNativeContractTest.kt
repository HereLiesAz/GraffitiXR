package com.hereliesaz.graffitixr.nativebridge

import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Pins [HomographyTrackerNative]'s four `native*` methods' Kotlin-side descriptors, the same way
 * [YuvConverterContractTest] pins `nativeYuvToRgbaBitmap`'s.
 *
 * This is only HALF the boundary, and on its own does not guard against the failure its old doc
 * comment described: JNI resolves a non-overloaded `external fun` by **short name**, not by
 * descriptor (see [NativeMethodAritySignatureTest]'s doc for why that matters) — so this test
 * alone would stay green through a parameter reorder on the C++ side, which is exactly the
 * mistake that breaks silently (the wrong argument lands in the wrong register; no build failure,
 * no link failure, no `UnsatisfiedLinkError`). [NativeMethodAritySignatureTest] is what actually
 * cross-checks these four names' arity against `GraffitiJNI.cpp`; this test only catches the
 * Kotlin side changing its OWN descriptor (a renamed/retyped parameter) without a matching native
 * change — still worth pinning, just not the whole story.
 */
class HomographyTrackerNativeContractTest {

    private val frozenDescriptors = mapOf(
        "nativeHomographySetReference" to "(Landroid/graphics/Bitmap;FF)Z",
        "nativeHomographyHasReference" to "()Z",
        "nativeHomographyReset" to "()V",
        "nativeHomographyTrack" to "(Landroid/graphics/Bitmap;FFFF[F)Z",
    )

    @Test
    fun `every native method has the frozen JNI descriptor`() {
        frozenDescriptors.forEach { (name, descriptor) ->
            val m = HomographyTrackerNative::class.java.declaredMethods.singleOrNull { it.name == name }
            assertNotNull(
                "HomographyTrackerNative must expose exactly one $name — the C++ symbol " +
                    "Java_com_hereliesaz_graffitixr_nativebridge_HomographyTrackerNative_$name " +
                    "is resolved by exact name.",
                m,
            )
            assertEquals("$name descriptor drifted from GraffitiJNI.cpp", descriptor, jniDescriptorOf(m!!))
        }
    }

    /** Builds a JNI method descriptor from a [Method]'s erased parameter/return types. */
    private fun jniDescriptorOf(method: Method): String =
        method.parameterTypes.joinToString(prefix = "(", postfix = ")", separator = "") {
            jniTypeOf(it)
        } + jniTypeOf(method.returnType)

    private fun jniTypeOf(type: Class<*>): String = when {
        type == Void.TYPE -> "V"
        type == Boolean::class.javaPrimitiveType -> "Z"
        type == Byte::class.javaPrimitiveType -> "B"
        type == Char::class.javaPrimitiveType -> "C"
        type == Short::class.javaPrimitiveType -> "S"
        type == Int::class.javaPrimitiveType -> "I"
        type == Long::class.javaPrimitiveType -> "J"
        type == Float::class.javaPrimitiveType -> "F"
        type == Double::class.javaPrimitiveType -> "D"
        type.isArray -> "[" + jniTypeOf(type.componentType!!)
        else -> "L" + type.name.replace('.', '/') + ";"
    }
}
