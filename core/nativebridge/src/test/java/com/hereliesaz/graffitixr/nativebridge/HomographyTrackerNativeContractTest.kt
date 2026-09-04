package com.hereliesaz.graffitixr.nativebridge

import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Guards the frozen JNI ABI between GraffitiJNI.cpp and [HomographyTrackerNative]'s four
 * `native*` methods, the same way [YuvConverterContractTest] guards `nativeYuvToRgbaBitmap`:
 * `Java_com_hereliesaz_graffitixr_nativebridge_HomographyTrackerNative_native*` is resolved by
 * the JVM at load time by exact class + method + descriptor, so a renamed method or reordered
 * parameter silently mangles the symbol and only fails at first invocation (UnsatisfiedLinkError)
 * — user-visible as the ARCore-unavailable fallback simply not tracking. This locks the
 * descriptors here instead.
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
