package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [Footprint.Region] is a **wire format**, not an internal enum.
 *
 * `Fingerprint.regions` stores `Region.ordinal` as one byte per point. Kotlin writes those bytes;
 * `MobileGS.cpp` reads them and decides which points the reloc PnP is allowed to see. Nothing in
 * either toolchain checks that the two agree — no shared header, no generated binding, no JNI
 * descriptor. Reorder the enum and every fingerprint in the field silently inverts: the backbone
 * becomes the painted-over set and the target stops locking, with no error anywhere.
 *
 * So the agreement is pinned here, from both directions:
 *
 *  - the ordinals themselves, so a reorder fails a Kotlin test;
 *  - the literal C++ constants, read out of `MobileGS.h`, so a change on that side fails too.
 *
 * The second assertion reads a source file, which is unusual and deliberate. The alternative is a
 * comment saying "keep these in sync", and this codebase has already learned what those are worth.
 */
class FootprintRegionWireTest {

    /**
     * The ordinals, written out. Deliberately literal: deriving them from the enum would assert
     * `ordinal == ordinal` and pass under exactly the reorder this test exists to catch.
     */
    @Test
    fun `region ordinals are the persisted wire values`() {
        assertEquals(0, Footprint.Region.INSIDE.ordinal)
        assertEquals(1, Footprint.Region.BAND.ordinal)
        assertEquals(2, Footprint.Region.OUTSIDE.ordinal)
        assertEquals("no region may be added without a native counterpart", 3, Footprint.Region.entries.size)
    }

    @Test
    fun `the native constants match the Kotlin ordinals`() {
        val header = findRepoFile("core/nativebridge/src/main/cpp/include/MobileGS.h")
        val text = header.readText()
        val native = Regex("""kRegion(\w+)\s*=\s*(\d+)""").findAll(text)
            .associate { it.groupValues[1].uppercase() to it.groupValues[2].toInt() }

        assertTrue(
            "MobileGS.h declares no kRegion* constants — did the partition filter get removed " +
                "without this test being updated? Found: $native",
            native.size == 3,
        )
        for (r in Footprint.Region.entries) {
            assertEquals(
                "Footprint.Region.${r.name} is ${r.ordinal} in Kotlin but ${native[r.name]} in " +
                    "MobileGS.h; the stored bytes mean different things on the two sides",
                r.ordinal, native[r.name],
            )
        }
    }

    /**
     * The reloc filter must keep only OUTSIDE. Pinned as a literal comparison against the native
     * constant name used in `MobileGS.cpp`, because "exclude INSIDE" and "keep only OUTSIDE" differ
     * precisely on the BAND — the region whose whole purpose is to be trusted by neither side.
     */
    @Test
    fun `the reloc filter keeps only the backbone, not merely everything that is not inside`() {
        val src = findRepoFile("core/nativebridge/src/main/cpp/MobileGS.cpp").readText()
        assertTrue(
            "expected the correspondence build to skip anything that is not kRegionOutside",
            src.contains("wallRegions[match[0].trainIdx] != kRegionOutside"),
        )
    }

    private fun findRepoFile(relative: String): File {
        // Test working directories differ between Gradle and IDE runs, so walk up rather than
        // assuming one. Failing loudly here beats a silently-skipped assertion.
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, relative)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative from ${File("").absolutePath}")
    }
}
