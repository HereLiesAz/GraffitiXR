package com.hereliesaz.graffitixr.feature.ar.eval

/**
 * Everything needed to reconstruct what produced a `DriftCostProbe` CSV — written as a JSON sidecar
 * next to it (`EVALUATION.md` §3.2, todo 6a.1).
 *
 * The rule that motivates this: **a CSV without a sidecar is not evidence.** A run's numbers are
 * only interpretable against the parameter values, the build, and the replay determinism settings
 * that produced them, and none of those are recoverable from the CSV afterwards. Two runs that
 * differ only in an unrecorded constant look like noise; two that differ only in device thermal
 * state look like a parameter effect.
 *
 * Deliberately hand-rolled rather than serialized through a library: this module has no
 * kotlinx-serialization dependency, the schema is flat, and the file is written once per run.
 */
data class EvalRunIdentity(
    /** Git commit the build came from. `"unknown"` when not injected at build time. */
    val gitCommit: String,
    /** Playback recording this run replayed, or null for a live session. */
    val recordingName: String? = null,
    /** Content hash of that recording, so a re-recorded file with the same name is distinguishable. */
    val recordingSha256: String? = null,
    val deviceModel: String,
    val androidRelease: String,
    /** "dual" or "mono" — the camera configuration under test. */
    val deviceClass: String,
    /**
     * Fixed RNG seed for the run, or null if the run was not seeded.
     *
     * `solvePnPRansac` draws random samples, so two replays of the same recording can differ without
     * this. An unseeded A/B is not a controlled comparison, and the difference it reports may be
     * entirely RANSAC.
     */
    val rngSeed: Long? = null,
    /**
     * True when relocalization ran inline on a fixed frame cadence rather than on its own thread.
     *
     * Async numbers are what users get; sync numbers are what is comparable between runs. Reporting
     * one while the reader assumes the other is how scheduling noise becomes a parameter effect.
     */
    val syncReloc: Boolean = false,
    /** Every tunable that was in force, `PARAMETERS.md` name → value. */
    val parameters: Map<String, String> = emptyMap(),
) {
    /** Sidecar filename for a CSV named `eval_mono_123.csv` → `eval_mono_123.run.json`. */
    fun sidecarNameFor(csvName: String): String = csvName.removeSuffix(".csv") + ".run.json"

    fun toJson(): String {
        val sb = StringBuilder(256)
        sb.append("{\n")
        val entries = buildList {
            add("gitCommit" to jsonString(gitCommit))
            add("recordingName" to (recordingName?.let { jsonString(it) } ?: "null"))
            add("recordingSha256" to (recordingSha256?.let { jsonString(it) } ?: "null"))
            add("deviceModel" to jsonString(deviceModel))
            add("androidRelease" to jsonString(androidRelease))
            add("deviceClass" to jsonString(deviceClass))
            add("rngSeed" to (rngSeed?.toString() ?: "null"))
            add("syncReloc" to syncReloc.toString())
            add("parameters" to parameters.entries
                .sortedBy { it.key }   // stable order so two sidecars diff cleanly
                .joinToString(prefix = "{", postfix = "}") { "${jsonString(it.key)}:${jsonString(it.value)}" })
        }
        entries.forEachIndexed { i, (k, v) ->
            sb.append("  ").append(jsonString(k)).append(": ").append(v)
            if (i != entries.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("}\n")
        return sb.toString()
    }

    private companion object {
        /** Minimal JSON string escaping — enough for paths, model names and parameter values. */
        fun jsonString(v: String): String {
            val sb = StringBuilder(v.length + 2)
            sb.append('"')
            for (c in v) when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
