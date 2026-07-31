package com.hereliesaz.graffitixr.feature.ar.eval

/** One throttled measurement tick. stageMs is indexed by the stage contract: 0=voxelUpdate,
 *  1=voxelKeyframe, 2=surfaceMesh, 3=draw, 4=pnpReloc. */
data class EvalSample(
    val tsMs: Long,
    val deviceClass: String,   // "dual" or "mono"
    val marksVisible: Boolean,
    val errMm: Float,          // pose error vs mark-PnP truth; -1 when marks not visible
    val errDeg: Float,
    val jitterMm: Float,
    val availability: Float,
    val stageMs: FloatArray,   // length 5
    val cpuPct: Float,
    val batteryMa: Float,      // BatteryManager CURRENT_NOW (µA→mA); negative = discharging
    val tempC: Float,
    val nativeHeapKb: Long,    // Debug.getNativeHeapAllocatedSize()/1024 — memory cost proxy
    // Relocalization diagnostics (EVALUATION.md §6: "log the reject histogram"). Without these a
    // null result is a null result; with them it is a diagnosis, and a configuration whose failures
    // are NO_FEATURES needs different work from one whose failures are FEW_INLIERS.
    // -1 = not sampled, which is distinct from 0. Zero detected features is a real measurement.
    val relocReject: Int = -1,      // RelocReject ordinal
    val relocMatches: Int = -1,
    val relocInliers: Int = -1,
    val relocDetected: Int = -1,
    // The remaining two RelocDiagnostics fields. obliquityDeg in particular is the quantity
    // PlaneMarksObliquityTest predicts the error scales with, so having it per-row is what lets a
    // device run be compared against PAPER.md 8.1's curve instead of argued about.
    val relocObliquityDeg: Int = -1,
    val relocRectifiedCorr: Int = -1,
)

object EvalSampleLog {

    /**
     * The single source of truth for the CSV shape.
     *
     * Header and row used to be written out independently, which is the class of bug that silently
     * corrupts every downstream analysis: add a field to one and the columns shift under every
     * consumer without anything failing. [toCsvRow] is checked against this list at runtime and
     * `EvalSampleLogTest` checks it again in CI.
     */
    val COLUMNS: List<String> = listOf(
        "tsMs", "deviceClass", "marksVisible", "errMm", "errDeg", "jitterMm", "availability",
        "voxelUpdateMs", "voxelKeyframeMs", "surfaceMeshMs", "drawMs", "pnpRelocMs",
        "cpuPct", "batteryMa", "tempC", "nativeHeapKb",
        "relocReject", "relocMatches", "relocInliers", "relocDetected",
        "relocObliquityDeg", "relocRectifiedCorr",
    )

    const val NOT_SAMPLED = -1

    val CSV_HEADER: String = COLUMNS.joinToString(",")

    /** Field values for [s], in [COLUMNS] order. */
    fun fields(s: EvalSample): List<String> {
        val st = FloatArray(5).also { System.arraycopy(s.stageMs, 0, it, 0, minOf(5, s.stageMs.size)) }
        return listOf(
            s.tsMs.toString(), csvEscape(s.deviceClass), s.marksVisible.toString(),
            s.errMm.toString(), s.errDeg.toString(), s.jitterMm.toString(), s.availability.toString(),
            st[0].toString(), st[1].toString(), st[2].toString(), st[3].toString(), st[4].toString(),
            s.cpuPct.toString(), s.batteryMa.toString(), s.tempC.toString(), s.nativeHeapKb.toString(),
            s.relocReject.toString(), s.relocMatches.toString(),
            s.relocInliers.toString(), s.relocDetected.toString(),
            s.relocObliquityDeg.toString(), s.relocRectifiedCorr.toString(),
        )
    }

    fun toCsvRow(s: EvalSample): String {
        val f = fields(s)
        // Belt-and-braces only. `fields()` returns a literal list, so its arity is a source constant
        // and no input can make this fire at runtime — the real guard is EvalSampleLogTest, which
        // catches the drift at build time. Kept because it costs nothing and localizes the failure
        // if someone later makes `fields()` conditional. (`check`, not `assert`: `assert` is gated
        // by -ea and would be inert in exactly the builds that write eval logs.)
        check(f.size == COLUMNS.size) {
            "CSV shape drift: ${f.size} fields for ${COLUMNS.size} columns"
        }
        return f.joinToString(",")
    }

    /**
     * Quote a value that could otherwise break the column alignment.
     *
     * `deviceClass` is a free-form string reaching the log from a caller, and one comma in it shifts
     * every subsequent column of that row — silently, since the file still parses.
     */
    fun csvEscape(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else {
            v
        }
}
