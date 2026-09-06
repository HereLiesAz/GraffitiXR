package com.hereliesaz.graffitixr.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JNI resolves a non-overloaded `external fun` by its **short name** —
 * `Java_<pkg>_<Class>_<method>` — and does **not** check the descriptor. Add a parameter on one side
 * only and nothing fails to build, nothing fails to link, and nothing throws
 * `UnsatisfiedLinkError`: the native function simply reads whatever happens to be in the next
 * argument register. That is a use of uninitialised memory that presents as intermittent corruption
 * far from its cause.
 *
 * This project has already paid once for trusting a name-based binding — `Fingerprint.fromNative`,
 * where adding a field to the data class silently broke the lookup twice and `setWallFingerprint`
 * returned null on every capture. That one is now pinned by `FingerprintJniContractTest`; the
 * `native*` methods were not pinned by anything.
 *
 * So: parameter counts, both sides, from source. A count is not a full descriptor check, but it
 * catches the failure that actually happens — a parameter added or removed on one side of the
 * boundary. Types are still the reviewer's job.
 *
 * Covers every `native*`-exporting Kotlin class in this module, not just `SlamManager` — an
 * earlier version of this test covered only `SlamManager`, which meant the 6 natives on
 * `HomographyTrackerNative`, `YuvConverter`, and `NativeCrashHandler` had no cross-file arity
 * check at all (a class-specific reflection test,
 * [HomographyTrackerNativeContractTest], separately pins Kotlin's own descriptors but never opens
 * `GraffitiJNI.cpp` — it would stay green through exactly the reorder this test exists to catch).
 */
class NativeMethodAritySignatureTest {

    @Test
    fun `every native method declares the same parameter count on both sides`() {
        val kotlin = KOTLIN_SRCS.joinToString("\n") { File(repoRoot(), it).readText() }
        val cpp = File(repoRoot(), CPP_SRC).readText()

        val kotlinArities = Regex(
            """(?:private\s+)?external fun (native\w+)\s*\(([^)]*)\)""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(kotlin).associate { m ->
            m.groupValues[1] to countKotlinParams(m.groupValues[2])
        }

        assertTrue(
            "found no `external fun native*` declarations in ${KOTLIN_SRCS.joinToString()} — the " +
                "regex, not the code, is probably what changed",
            kotlinArities.size > 20,
        )

        val cppArities = Regex(
            """Java_com_hereliesaz_graffitixr_nativebridge_(?:$JNI_CLASSES)_(\w+)\s*\(([^)]*)\)""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(cpp).associate { m ->
            // Every JNI entry point begins with (JNIEnv*, jobject); the Kotlin declaration has
            // neither, so they are subtracted rather than matched.
            m.groupValues[1] to (countCppParams(m.groupValues[2]) - 2)
        }

        // Orphans on the C++ side. The intersection below structurally cannot see these — a JNI
        // entry point with no Kotlin declaration is dead export surface that nothing will ever call,
        // and two of them survived a feature deletion here before this assertion existed. The
        // reverse direction (Kotlin with no C++) is not checked here because it fails loudly at
        // runtime with UnsatisfiedLinkError; a C++ orphan fails silently, forever.
        val orphans = (cppArities.keys - kotlinArities.keys).sorted()
        assertTrue(
            "GraffitiJNI.cpp exports $orphans with no matching `external fun` in any of " +
                "${KOTLIN_SRCS.joinToString()}. Nothing can call them; delete the export or add " +
                "the declaration.",
            orphans.isEmpty(),
        )

        val checked = kotlinArities.keys.intersect(cppArities.keys)
        assertTrue(
            "no native method names matched between the two files; one of the two regexes is wrong",
            checked.size > 20,
        )
        for (name in checked.sorted()) {
            assertEquals(
                "$name: Kotlin declares ${kotlinArities[name]} parameters, GraffitiJNI.cpp takes " +
                    "${cppArities[name]}. JNI binds by name and will not catch this at runtime.",
                kotlinArities[name], cppArities[name],
            )
        }
    }

    /**
     * The one that just changed, named explicitly so a future edit that drops `regions` from either
     * side fails with a message about the partition rather than an anonymous arity mismatch.
     */
    @Test
    fun `the metric fingerprint restore carries the Phase 2 regions array`() {
        val kotlin = File(repoRoot(), SLAM_MANAGER_SRC).readText()
        val cpp = File(repoRoot(), CPP_SRC).readText()
        assertTrue(
            "Kotlin's nativeRestoreWallFingerprintMetric no longer declares `regions`",
            Regex("""external fun nativeRestoreWallFingerprintMetric\s*\([^)]*regions:\s*ByteArray""",
                RegexOption.DOT_MATCHES_ALL).containsMatchIn(kotlin),
        )
        assertTrue(
            "GraffitiJNI.cpp's nativeRestoreWallFingerprintMetric no longer takes regionsArray",
            Regex("""Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeRestoreWallFingerprintMetric\s*\([^)]*jbyteArray regionsArray""",
                RegexOption.DOT_MATCHES_ALL).containsMatchIn(cpp),
        )
    }

    /**
     * `getRelocDiagnostics`'s KDoc used to spell out the packed array's width as a word in prose,
     * and that word went stale three times as the array grew (three, four, six, nine). It now
     * references [RELOC_DIAGNOSTICS_ARRAY_SIZE] instead of repeating the number; this
     * test is what keeps that constant honest against the actual `jint vals[...]` literal in
     * GraffitiJNI.cpp so a fifth drift fails here rather than silently.
     */
    @Test
    fun `RELOC_DIAGNOSTICS_ARRAY_SIZE matches GraffitiJNI's packed diagnostics array literal`() {
        val cpp = File(repoRoot(), CPP_SRC).readText()
        val match = Regex("""jint vals\[(\d+)]\s*=""").find(cpp)
        assertTrue(
            "could not find `jint vals[N] = ...` in $CPP_SRC — nativeGetRelocDiagnostics's packing " +
                "array literal, or its shape, changed",
            match != null,
        )
        val cppSize = match!!.groupValues[1].toInt()
        assertEquals(
            "RELOC_DIAGNOSTICS_ARRAY_SIZE (${RELOC_DIAGNOSTICS_ARRAY_SIZE}) " +
                "no longer matches GraffitiJNI.cpp's `jint vals[$cppSize]` — update the Kotlin " +
                "constant (and its KDoc) to match.",
            cppSize, RELOC_DIAGNOSTICS_ARRAY_SIZE,
        )
    }

    private companion object {
        private const val NATIVEBRIDGE_KOTLIN_DIR =
            "core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge"
        const val SLAM_MANAGER_SRC = "$NATIVEBRIDGE_KOTLIN_DIR/SlamManager.kt"

        /** Every Kotlin class in this module that exports `native*` methods. */
        val KOTLIN_SRCS = listOf(
            SLAM_MANAGER_SRC,
            "$NATIVEBRIDGE_KOTLIN_DIR/HomographyTrackerNative.kt",
            "$NATIVEBRIDGE_KOTLIN_DIR/YuvConverter.kt",
            "$NATIVEBRIDGE_KOTLIN_DIR/NativeCrashHandler.kt",
        )

        /** The JNI class-name alternation for [KOTLIN_SRCS] — keep the two lists in sync. */
        const val JNI_CLASSES = "SlamManager|HomographyTrackerNative|YuvConverter|NativeCrashHandler"

        const val CPP_SRC = "core/nativebridge/src/main/cpp/GraffitiJNI.cpp"

        /** Split a parameter list on top-level commas; generics/defaults don't appear in these decls. */
        fun countKotlinParams(params: String): Int = splitTopLevel(params)

        fun countCppParams(params: String): Int = splitTopLevel(params)

        fun splitTopLevel(params: String): Int {
            // A multi-line Kotlin parameter list conventionally ends with a trailing comma (e.g.
            // HomographyTrackerNative.nativeHomographyTrack, YuvConverter.nativeYuvToRgbaBitmap) —
            // without stripping it, that comma counts as one more separator than there are
            // parameters, over-counting by exactly one and false-failing this test against C++
            // declarations (which have no such convention) even when both sides genuinely agree.
            val body = params.trim().removeSuffix(",")
            if (body.isEmpty()) return 0
            var depth = 0
            var count = 1
            for (c in body) {
                when (c) {
                    '<', '(', '[' -> depth++
                    '>', ')', ']' -> depth--
                    ',' -> if (depth == 0) count++
                }
            }
            return count
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, SLAM_MANAGER_SRC).isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the repo root from ${File("").absolutePath}")
    }
}
