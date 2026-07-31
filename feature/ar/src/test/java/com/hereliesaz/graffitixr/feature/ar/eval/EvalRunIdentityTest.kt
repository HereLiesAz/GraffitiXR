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

    /** A name without the extension must not silently produce a collidng or extension-less file. */
    @Test
    fun `sidecar name handles a csv name without the extension`() {
        assertEquals("eval_mono_123.run.json", identity().sidecarNameFor("eval_mono_123"))
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
        // Balanced quotes is a cheap proxy for "still parseable".
        assertEquals(0, j.count { it == '"' } % 2)
    }
}
