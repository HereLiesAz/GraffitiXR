package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `IMPLEMENTATION.md` **4.5** — [SearchRadius] and [KeypointGrid] are Kotlin *references* that
 * `SearchRadius.h` and `KeypointGrid.h` transliterate, and the running code is the C++ one.
 *
 * That is an uncomfortable arrangement: every test in `SearchRadiusTest` and `KeypointGridTest`
 * exercises a copy of the algorithm that never executes on a device. There is no native test
 * harness in this project, and adding one — a second toolchain, a gtest dependency, a CI target —
 * is a larger change than the phase it would be serving.
 *
 * So this file pins what *can* be pinned from Kotlin: that the two sides agree on the numbers and
 * on the handful of structural choices where a plausible rewrite silently changes behaviour. It
 * cannot prove the C++ computes the same function. It can prove nobody edited one side's constant
 * and left the other, which is the divergence with the highest probability and the lowest
 * visibility — a radius quietly twice as wide produces no error, no crash and no wrong pixel, just
 * a corroboration rate that drifts away from the one the experiments were designed against.
 *
 * The reads are source-text reads, like `FootprintRegionWireTest`'s, and for the same stated reason:
 * the alternative is a comment asking for the two to be kept in sync.
 */
class CorroborationTranslitTest {

    @Test
    fun `the native search-radius constants are the Kotlin ones`() {
        val native = constants(source("core/nativebridge/src/main/cpp/include/SearchRadius.h"))
        assertEquals(
            "SearchRadius.h declares an unexpected set of constants; found $native",
            setOf("kRho", "kMinPx", "kMaxPx", "kErrGain", "kReprojGain", "kNotMeasured"),
            native.keys,
        )
        assertEquals("rho", SearchRadius.RHO, native.getValue("kRho"), 0f)
        assertEquals("min", SearchRadius.MIN_PX, native.getValue("kMinPx"), 0f)
        assertEquals("max", SearchRadius.MAX_PX, native.getValue("kMaxPx"), 0f)
        assertEquals("err gain", SearchRadius.ERR_GAIN, native.getValue("kErrGain"), 0f)
        assertEquals("reproj gain", SearchRadius.REPROJ_GAIN, native.getValue("kReprojGain"), 0f)
        assertEquals("sentinel", SearchRadius.NOT_MEASURED, native.getValue("kNotMeasured"), 0f)
    }

    /**
     * The three asymmetries the Kotlin tests exist to protect, each phrased as the C++ expression
     * that carries it. A transliteration that reversed any of them would still compile, still run,
     * and still produce a number in the right range.
     */
    @Test
    fun `the native search radius keeps the deliberate asymmetries`() {
        val src = source("core/nativebridge/src/main/cpp/include/SearchRadius.h").readText()
        // Degenerate geometry must fail WIDE. A too-tight search returns nothing and reads
        // downstream as "the wall does not corroborate", which is a confident wrong answer.
        assertTrue(
            "degenerate geometry must return maxPx, not minPx",
            Regex("""rho\s*<=\s*0\.0f\)\s*\{\s*\n\s*return maxPx;""").containsMatchIn(src),
        )
        // A negative reading is "not measured" and contributes nothing — for BOTH drift inputs.
        assertTrue(
            "poseErrMm must be gated on >= 0",
            src.contains("(poseErrMm >= 0.0f && std::isfinite(poseErrMm))"),
        )
        assertTrue(
            "reprojErrPx must be gated on >= 0",
            src.contains("(reprojErrPx >= 0.0f && std::isfinite(reprojErrPx))"),
        )
        // The reprojection residual is already pixels. Multiplying it by pxPerMeter is the error the
        // Kotlin test `the reprojection term is pixels and is not reprojected again` guards against,
        // and pxPerMeter is in scope one line above it in the C++.
        assertTrue(
            "the reprojection term must not be scaled by pxPerMeter",
            src.contains("reprojGain * reprojErrPx"),
        )
        // Anisotropy averages, and the geometric mean is what makes rho scale-free.
        assertTrue(
            "the design extent must be the geometric mean",
            src.contains("std::sqrt(halfW * halfH)"),
        )
    }

    /**
     * The grid's two structural traps, both of which the Kotlin tests caught during development and
     * neither of which produces anything but "slightly fewer corroborated features" when wrong.
     */
    @Test
    fun `the native grid sweeps a cell range and rejects out-of-range queries unclamped`() {
        val src = source("core/nativebridge/src/main/cpp/include/KeypointGrid.h").readText()
        // The out-of-range test must run on the UNCLAMPED indices. Clamping first maps a distant
        // query onto the edge cells and returns their contents — on a one-cell grid, every keypoint
        // in the frame for a query nowhere near any of them.
        assertTrue(
            "the raw cell indices must be tested before they are clamped",
            src.contains("if (c1raw < 0 || c0raw > mCols - 1 || r1raw < 0 || r0raw > mRows - 1) return;"),
        )
        val clampAt = src.indexOf("const int c0 = clampi(c0raw")
        val rejectAt = src.indexOf("if (c1raw < 0 ||")
        assertTrue("the reject must come before the clamp", rejectAt in 0 until clampAt)
        // floor, not truncation. A query half a cell left of the origin truncates to 0 instead of
        // -1, and the reject above would never fire on that side.
        assertTrue(
            "cellFloor must use std::floor, not an int cast",
            src.contains("std::floor((double)q)"),
        )
        // ...and the DIVISION must stay in float, matching the Kotlin reference's Float operands.
        // Computing the quotient in double is more accurate and therefore different: at a boundary
        // where the float quotient rounds up to an exact integer and the double one does not, the
        // two implementations disagree about the cell and the out-of-range reject fires on one side
        // only. This assertion previously pinned the double form, i.e. the test whose job is proving
        // the two agree was pinning the one expression where they could not.
        assertTrue(
            "the cell quotient must be computed in float, as the Kotlin reference does",
            src.contains("const float q = (v - minV) / mCell;"),
        )
        // A cell RANGE, not a fixed 3x3 — the caller queries one grid at several radii.
        assertTrue(
            "the sweep must be over the computed range",
            src.contains("for (int r = r0; r <= r1; ++r)") && src.contains("for (int c = c0; c <= c1; ++c)"),
        )
        // A non-finite keypoint must not poison the bounds and collapse the grid to one cell,
        // which would turn the local search silently back into a global one.
        assertTrue(
            "build must skip non-finite keypoints when computing bounds",
            src.contains("if (!std::isfinite(kp.pt.x) || !std::isfinite(kp.pt.y)) continue;"),
        )
    }

    /**
     * `IMPLEMENTATION.md` **4.7** — the corroboration ratio must be its own constant, and it must be
     * looser than the reloc one. Looser is the whole claim of the phase: the spatial constraint
     * collapses the false-positive population, and that is what buys back the recall a global
     * search had to throw away. If the two were ever equal the constraint would be pure cost.
     */
    @Test
    fun `the corroboration Lowe ratio is separate from the reloc one and looser`() {
        val native = constants(source("core/nativebridge/src/main/cpp/include/MobileGS.h"))
        val reloc = native["kRelocLoweRatio"]
        val corrob = native["kCorrobLoweRatio"]
        assertTrue("MobileGS.h must declare kRelocLoweRatio; found ${native.keys}", reloc != null)
        assertTrue("MobileGS.h must declare kCorrobLoweRatio; found ${native.keys}", corrob != null)
        assertTrue(
            "the corroboration ratio ($corrob) must be looser than the reloc ratio ($reloc)",
            corrob!! > reloc!!,
        )
        // Literal, so a silent edit to a value E7 is waiting on shows up as a test change.
        assertEquals(0.75f, reloc, 0f)
        assertEquals(0.85f, corrob, 0f)

        // And the gated match must actually use it. A separate constant nothing reads is the
        // antipattern this project has shipped before.
        val cpp = source("core/nativebridge/src/main/cpp/MobileGS.cpp").readText()
        assertTrue(
            "the gated corroboration match must apply kCorrobLoweRatio",
            cpp.contains("best < kCorrobLoweRatio * second"),
        )
        assertTrue(
            "no bare 0.75f Lowe literal may survive in MobileGS.cpp",
            !Regex("""distance\s*<\s*0\.75f""").containsMatchIn(cpp) &&
                !Regex("""<\s*0\.75f\s*\*\s*\w+\[1\]""").containsMatchIn(cpp),
        )
    }

    /**
     * `IMPLEMENTATION.md` **4.8** — zero predicted-visible is "the artist is looking away", not
     * "the wall does not corroborate". Pinned against the source because the two differ by one
     * ternary and the wrong one is the shorter, more obvious expression.
     */
    @Test
    fun `zero predicted-visible publishes the unmeasured sentinel, not zero`() {
        val cpp = source("core/nativebridge/src/main/cpp/MobileGS.cpp").readText()
        assertTrue(
            "confidence must fall back to kCorroborationUnmeasured when nothing is predicted visible",
            cpp.contains("predicted > 0 ? (float)matched / (float)predicted : kCorroborationUnmeasured"),
        )
    }

    /** `NAME = <number>f` pairs from a C++ header, floats only. */
    private fun constants(file: File): Map<String, Float> =
        Regex("""constexpr float (\w+)\s*=\s*(-?[\d.]+)f""")
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2].toFloat() }

    private fun source(relative: String): File {
        // Test working directories differ between Gradle and IDE runs, so walk up rather than
        // assuming one. Failing loudly here beats a silently-skipped assertion.
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, relative)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError("could not find $relative from ${File("").absolutePath}")
    }
}
