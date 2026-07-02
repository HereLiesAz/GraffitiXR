package com.hereliesaz.graffitixr.common.model

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.KeyPoint

/**
 * Guards the frozen JNI ABI between GraffitiJNI.cpp and [Fingerprint.fromNative].
 *
 * `buildFingerprintObject` (C++) looks the factory up by the exact name/descriptor in
 * [Fingerprint.JNI_FACTORY_NAME]/[Fingerprint.JNI_FACTORY_DESCRIPTOR]. Editing the factory's
 * signature — or reverting native code to the raw constructor, whose descriptor changes whenever
 * a defaulted field is added to the data class — silently breaks wall-fingerprint capture: the
 * native lookup returns null and setWallFingerprint yields null on every call. That exact failure
 * has shipped twice (patchData, then markCenterLocal). If this test fails, fix the factory back
 * to the frozen signature or add a NEW factory for the new shape and update GraffitiJNI.cpp in
 * the same change.
 */
class FingerprintJniContractTest {

    @Test
    fun `fromNative exists, is static, and matches the frozen JNI descriptor`() {
        val method = Fingerprint::class.java.methods.singleOrNull {
            it.name == Fingerprint.JNI_FACTORY_NAME && Modifier.isStatic(it.modifiers)
        }
        assertNotNull(
            "Fingerprint must expose exactly one static ${Fingerprint.JNI_FACTORY_NAME} — " +
                "GraffitiJNI.cpp resolves it with GetStaticMethodID",
            method,
        )
        assertEquals(Fingerprint.JNI_FACTORY_DESCRIPTOR, jniDescriptorOf(method!!))
    }

    @Test
    fun `fromNative passes every field through unchanged`() {
        val keypoints = listOf(KeyPoint(1f, 2f, 3f, 4f, 5f, 6, 7))
        val points3d = listOf(0.5f, -1.5f, 2f)
        val descriptors = byteArrayOf(1, 2, 3, 4)
        val patch = byteArrayOf(9, 8)
        val center = listOf(0.1f, 0.2f, 0.3f)

        val fp = Fingerprint.fromNative(keypoints, points3d, descriptors, 2, 2, 0, patch, center)

        assertEquals(keypoints, fp.keypoints)
        assertEquals(points3d, fp.points3d)
        assertArrayEquals(descriptors, fp.descriptorsData)
        assertEquals(2, fp.descriptorsRows)
        assertEquals(2, fp.descriptorsCols)
        assertEquals(0, fp.descriptorsType)
        assertArrayEquals(patch, fp.patchData)
        assertEquals(center, fp.markCenterLocal)
    }

    @Test
    fun `descriptor constant itself stays frozen`() {
        // Belt and braces: if someone "helpfully" regenerates the constant to match a changed
        // signature, this literal still fails the build until GraffitiJNI.cpp is reconciled.
        assertEquals(
            "(Ljava/util/List;Ljava/util/List;[BIII[BLjava/util/List;)" +
                "Lcom/hereliesaz/graffitixr/common/model/Fingerprint;",
            Fingerprint.JNI_FACTORY_DESCRIPTOR,
        )
        assertEquals("fromNative", Fingerprint.JNI_FACTORY_NAME)
    }

    /** Builds the JNI method descriptor for [method] from its erased parameter/return types. */
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

    init {
        // Sanity for the helper itself, so descriptor mismatches always mean a real ABI change.
        assertTrue(jniTypeOf(ByteArray::class.java) == "[B")
    }
}
