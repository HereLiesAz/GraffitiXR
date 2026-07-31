package com.hereliesaz.graffitixr.feature.ar.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IMPLEMENTATION.md todo **6a.1** — the run-identity sidecar.
 *
 * The rule it enforces (`EVALUATION.md` §3.2): a CSV without a sidecar is not evidence. Two runs
 * differing only in an unrecorded constant look like noise; two differing only in thermal state look
 * like a parameter effect. Neither is recoverable from the numbers afterwards.
 */
class EvalRunIdentityTest {

    private fun identity() = EvalRunIdentity(
        gitCommit = "419fbcb",
        recordingName = "wall_oblique_01.mp4",
        recordingSha256 = "abc123",
        deviceModel = "Pixel 8",
        androidRelease = "15",
        deviceClass = "mono",
        rngSeed = 42L,
        syncReloc = true,
        parameters = mapOf("CONF_FLOOR" to "0.5", "BASE_ALPHA" to "0.25"),
    )

    @Test
    fun `sidecar name is derived from the csv name`() {
        assertEquals("eval_mono_123.run.json", identity().sidecarNameFor("eval_mono_123.csv"))
    }

    /**
     * An extension-less input yields the SAME name as its `.csv` form — `removeSuffix` is a no-op
     * when the suffix is absent, so the two are indistinguishable in the output.
     *
     * Recorded as the actual behaviour rather than dressed up as collision-safety, which is what an
     * earlier version of this KDoc claimed while asserting the collision. It is unreachable today:
     * the only caller passes `File.name` for a file it just created as `eval_*.csv`. If a second
     * caller ever appears, this is the constraint it has to respect.
     */
    @Test
    fun `sidecar name is not disambiguated for an extension-less input`() {
        assertEquals("eval_mono_123.run.json", identity().sidecarNameFor("eval_mono_123"))
        assertEquals(
            "the two inputs collide by design; see the KDoc",
            identity().sidecarNameFor("eval_mono_123.csv"),
            identity().sidecarNameFor("eval_mono_123"),
        )
    }

    @Test
    fun `every field reaches the json`() {
        val j = identity().toJson()
        for (needle in listOf(
            "\"gitCommit\": \"419fbcb\"",
            "\"recordingName\": \"wall_oblique_01.mp4\"",
            "\"recordingSha256\": \"abc123\"",
            "\"deviceModel\": \"Pixel 8\"",
            "\"androidRelease\": \"15\"",
            "\"deviceClass\": \"mono\"",
            "\"rngSeed\": 42",
            "\"syncReloc\": true",
        )) {
            assertTrue("missing $needle in:\n$j", j.contains(needle))
        }
        assertTrue(j.contains("\"CONF_FLOOR\":\"0.5\""))
        assertTrue(j.contains("\"BASE_ALPHA\":\"0.25\""))
    }

    /**
     * An unseeded run must say so rather than reporting a plausible-looking seed. `solvePnPRansac`
     * draws random samples, so an unseeded A/B is not a controlled comparison and the difference it
     * reports may be entirely RANSAC — which the reader can only know if the field is honest.
     */
    @Test
    fun `an unseeded live run reports nulls, not defaults`() {
        val j = EvalRunIdentity(
            gitCommit = "unknown", deviceModel = "Pixel 8", androidRelease = "15",
            deviceClass = "dual",
        ).toJson()
        assertTrue(j.contains("\"rngSeed\": null"))
        assertTrue(j.contains("\"recordingName\": null"))
        assertTrue(j.contains("\"recordingSha256\": null"))
        assertTrue(j.contains("\"syncReloc\": false"))
        assertTrue(j.contains("\"parameters\": {}"))
    }

    /** Stable key order, so two sidecars from an A/B diff to just the parameter that changed. */
    @Test
    fun `parameters are emitted in a stable sorted order`() {
        val a = EvalRunIdentity(
            gitCommit = "x", deviceModel = "m", androidRelease = "15", deviceClass = "mono",
            parameters = linkedMapOf("zeta" to "1", "alpha" to "2", "mid" to "3"),
        ).toJson()
        val b = EvalRunIdentity(
            gitCommit = "x", deviceModel = "m", androidRelease = "15", deviceClass = "mono",
            parameters = linkedMapOf("mid" to "3", "alpha" to "2", "zeta" to "1"),
        ).toJson()
        assertEquals("insertion order must not change the output", a, b)
        assertTrue(a.indexOf("alpha") < a.indexOf("mid"))
        assertTrue(a.indexOf("mid") < a.indexOf("zeta"))
    }

    /** Model names and paths carry quotes and backslashes; unescaped, they produce invalid JSON. */
    @Test
    fun `strings needing escapes do not corrupt the json`() {
        val j = EvalRunIdentity(
            gitCommit = "a\"b", deviceModel = "back\\slash", androidRelease = "1\n5",
            deviceClass = "mono",
        ).toJson()
        assertTrue(j.contains("\"a\\\"b\""))
        assertTrue(j.contains("\"back\\\\slash\""))
        assertTrue(j.contains("\"1\\n5\""))
        assertStructurallyValidJson(j)
    }

    /** Control characters must become \uXXXX escapes rather than raw bytes in the output. */
    @Test
    fun `control characters are escaped`() {
        val j = EvalRunIdentity(
            // Built rather than written as a literal: a raw control byte in a source file is
            // fragile (editors and tooling silently eat it), and the escape form is easy to
            // mistake for the two-character sequence it is meant to test.
            gitCommit = "a" + 1.toChar() + "b",
            deviceModel = "m", androidRelease = "15", deviceClass = "mono",
        ).toJson()
        assertTrue("expected a \\u0001 escape in:\n$j", j.contains("\"a\\u0001b\""))
        assertTrue("no raw control byte may survive", j.none { it < ' ' && it != '\n' })
        assertStructurallyValidJson(j)
    }

    /** Parameter KEYS come from `PARAMETERS.md` names but are still strings that must be escaped. */
    @Test
    fun `a parameter key needing escapes stays valid`() {
        val j = EvalRunIdentity(
            gitCommit = "x", deviceModel = "m", androidRelease = "15", deviceClass = "mono",
            parameters = mapOf("odd\"key" to "va\\lue"),
        ).toJson()
        assertTrue(j.contains("\"odd\\\"key\""))
        assertTrue(j.contains("\"va\\\\lue\""))
        assertStructurallyValidJson(j)
    }

    @Test
    fun `the ordinary case is structurally valid too`() {
        assertStructurallyValidJson(identity().toJson())
    }

    /**
     * Escape-aware structural validation: every string closes, braces balance, and nothing nests
     * negative.
     *
     * The first version of this file used `j.count { it == '"' } % 2 == 0` as a "cheap proxy for
     * still parseable". It is not a proxy for that — it counts *escaped* quotes as delimiters, so a
     * correctly-escaped `"a\"b"` reads as unbalanced and the assertion fails on valid output. It did
     * fail, in CI, on correct code. A test whose failure mode is "the implementation was right" is
     * worse than no test: it trains you to loosen the assertion rather than look.
     */
    private fun assertStructurallyValidJson(j: String) {
        var depth = 0
        var inString = false
        var escaped = false
        var unescapedQuotes = 0
        for (c in j) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> { inString = false; unescapedQuotes++ }
                }
            } else {
                when (c) {
                    '"' -> { inString = true; unescapedQuotes++ }
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        assertTrue("closed more than was opened in:\n$j", depth >= 0)
                    }
                }
            }
        }
        assertEquals("unterminated string in:\n$j", false, inString)
        assertEquals("unbalanced braces in:\n$j", 0, depth)
        assertEquals("odd number of string delimiters in:\n$j", 0, unescapedQuotes % 2)
    }
}
