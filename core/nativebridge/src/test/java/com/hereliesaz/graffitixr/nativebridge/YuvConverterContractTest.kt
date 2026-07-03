package com.hereliesaz.graffitixr.nativebridge

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Guards the frozen JNI ABI between GraffitiJNI.cpp and [YuvConverter.nativeYuvToRgbaBitmap].
 *
 * `Java_com_hereliesaz_graffitixr_nativebridge_YuvConverter_nativeYuvToRgbaBitmap` is resolved by
 * the JVM at load time by exact class + method + descriptor. Renaming the method or reordering
 * its parameters silently mangles the symbol name and the call fails with UnsatisfiedLinkError at
 * first invocation — which is user-visible as a broken target capture. Locking the descriptor
 * here means signature drift fails the JVM test suite instead of only breaking at runtime.
 *
 * Mirrors the guard on `Fingerprint.fromNative` (FingerprintJniContractTest in core:common) — the
 * same class of bug shipped twice for Fingerprint before the frozen ABI was added.
 */
class YuvConverterContractTest {

    /** Frozen descriptor — do NOT edit. Any drift here is intentional and requires updating both
     *  YuvConverter.kt AND the C++ symbol name in GraffitiJNI.cpp. */
    private val frozenDescriptor: String =
        "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIIIILandroid/graphics/Bitmap;)V"

    @Test
    fun `native symbol has the frozen JNI descriptor`() {
        val m = YuvConverter::class.java.methods.singleOrNull { it.name == "nativeYuvToRgbaBitmap" }
        assertNotNull(
            "YuvConverter must expose exactly one nativeYuvToRgbaBitmap — the C++ symbol " +
                "Java_com_hereliesaz_graffitixr_nativebridge_YuvConverter_nativeYuvToRgbaBitmap " +
                "is resolved by exact name.",
            m,
        )
        // The Kotlin `external` on an object member compiles to a native instance method
        // (`this` is the object singleton), not static — matching JVM's native-method resolution.
        assertEquals(frozenDescriptor, jniDescriptorOf(m!!))
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
