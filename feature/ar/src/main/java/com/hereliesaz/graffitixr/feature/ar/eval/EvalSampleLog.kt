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
    // The backbone (F_out) counts, logged beside the totals rather than replacing them
    // (IMPLEMENTATION.md 2.11). Phase 2 changed what a reloc failure means: a run whose backbone
    // count collapses as the design is scaled up is a different result from one whose total matches
    // collapse, and without these columns the two are the same row. -1 = not sampled; 0 is a real
    // reading meaning F_out is empty, so it cannot double as the marker.
    val relocBackboneFeatures: Int = -1,
    val relocBackboneMatches: Int = -1,
    val relocBackboneInliers: Int = -1,
    /**
     * `IMPLEMENTATION.md` **4.6** — design features the pose predicts should be visible in this
     * frame, and how many of them the wall answered for. -1 = no gated corroboration attempt was
     * measured this tick.
     *
     * These are logged beside the reloc counts rather than folded into them because Phase 4 changed
     * what a corroboration number means: the pre-Phase-4 global fallback matched the whole design,
     * including the half behind the camera, so its count and a spatially-gated count are different
     * quantities and must not share a column.
     *
     * **-1, never 0.** Zero predicted-visible is a real reading — the artist has turned away from
     * the design, so there is nothing to corroborate against and a low ratio means nothing. That is
     * a different row from one where the corroboration path never ran, and collapsing the two would
     * put the "looking away" rows into the same bucket as the "gate never fired" rows, which is the
     * exact confusion 4.8 has to be able to resolve. The pairing matters too: zero predicted with
     * zero matched is benign, whereas a healthy predicted with zero matched is the wall failing to
     * corroborate, and only distinct columns can tell those apart.
     */
    val corrobPredicted: Int = -1,
    val corrobMatched: Int = -1,
    /**
     * The radius the last gated corroboration search used, and the mean reprojection error over the
     * last lock's PnP inliers, both in pixels. -1f = not measured.
     *
     * Floats, and carried on their own channel out of native for that reason: rounding these to
     * integers to share the int[] would flatten every sub-pixel reading to 0 or 1 at precisely the
     * tight radii Phase 4 exists to measure, so the column would be widest-open exactly where it
     * needs to be sharpest.
     *
     * **-1f, never 0f.** A near-zero reprojection error is what a perfect lock actually reports —
     * it is the *best* outcome this column can carry, not the absence of one — so zero cannot double
     * as the not-measured marker without making every ideal row indistinguishable from a row where
     * nothing was measured, and turning the healthiest evidence Phase 4 can produce into missing
     * data.
     *
     * The radius is a different case and an earlier version of this note got it wrong: it can
     * **never** be 0. `SearchRadius.pixels` clamps every path into `[MIN_PX, MAX_PX]` = `[4, 120]`,
     * and its degenerate branch returns the ceiling. So a `0` in this column is not a degenerate
     * reading to interpret — it is corrupt data, and should be treated as one.
     */
    val searchRadiusPx: Float = -1f,
    val relocReprojPx: Float = -1f,
    /**
     * Predicted-visible features the gated match **refused to test**, because their neighbourhood
     * held a single candidate and Lowe's ratio needs two. -1 = not sampled; 0 is a real reading and
     * the one that says the radius is comfortable.
     *
     * This column is why the phase is falsifiable. Skips deflate [corrobMatched] without touching
     * [corrobPredicted], so a radius too tight to find two candidates produces the same CSV
     * signature as a wall that has stopped corroborating — and the two call for opposite responses
     * (raise ρ vs. investigate the paint). At the `MIN_PX` floor a sparse frame can skip most of
     * what it predicted, so without this column E6 could report a confidence collapse and attribute
     * it to the wrong cause with no way to tell.
     */
    val corrobLoneSkips: Int = -1,
    /**
     * `IMPLEMENTATION.md` **3.3** — the last lock's inlier bounding box as a fraction of frame area.
     * -1f = not sampled.
     *
     * E5's column. Self-grow permanently mutates the map, so its gate has to be strict — and a
     * strict gate that is too strict is indistinguishable from one that never fired, because both
     * produce the same absence of promotions. This is the only signal that separates them.
     *
     * **-1f, never 0f.** A spread of 0 is a real reading: every inlier on one pixel, the
     * worst-conditioned pose the gate exists to reject. It is the row that matters most, and it must
     * not be filed as missing data.
     */
    val inlierSpread: Float = -1f,
    /**
     * Ordinals of the two REASON channels: why the gated corroboration match did or did not run, and
     * what self-grow did. `CorrobGate` / `GrowOutcome`, -1 when not sampled.
     *
     * Reasons rather than measurements, and the eval needs them for the same purpose the overlay
     * does: a run where corroboration never gated is not a bad result, it is a **void** one, and
     * without these columns it is indistinguishable from a run where the wall genuinely failed to
     * corroborate. Aggregating the two together is how a null result gets manufactured.
     */
    val corrobGate: Int = -1,
    val growOutcome: Int = -1,
    /**
     * `FusionState` ordinal — what pose fusion did, or why it did not run — and the standing
     * correction's magnitude in millimetres. -1 when not sampled.
     *
     * The correction magnitude is the number to plot against `errMm`: a run where fusion never
     * corrected and a run where it corrected wrongly both show drift, and only this separates them.
     */
    val fusionState: Int = -1,
    val fusionCorrectionMm: Float = -1f,
    /**
     * `rotationNeeded` **at the moment the active target was captured** — 0, 90, 180 or 270, or -1
     * when no target has been captured this session (a project restored from disk reads -1; see
     * below).
     *
     * **This is the independent variable of `EVALUATION.md` E0b**, and the distinction from
     * [liveRotationNeededDeg] is the whole point of having two columns. `PlaneMarks.backProject`
     * runs exactly once per target, inside `MetricFingerprintBuilder.buildSingle`, so whatever frame
     * relationship holds at capture is **baked into the fingerprint's 3D points** and cannot change
     * afterwards, however the device is subsequently held. That is why the capture rotation, not the
     * live one, is the variable to group by.
     *
     * Note the §8 mismatch this column was built to measure is **fixed as of Phase 0** — the capture
     * view is now rotated to match the intrinsics. The column is still the right one: it records the
     * condition a fingerprint was built under, which is what any before/after comparison needs.
     *
     * A first version of this column logged the live per-tick rotation instead, which is a
     * different quantity and mislabels the experiment: capture in portrait (skewed fingerprint),
     * then relocalize in landscape, and every defect-bearing row is filed under 0 — the *control*
     * condition — so a real §8 effect averages toward nothing and reads as a falsification. The
     * justification given for per-tick sampling was that `screenOrientation="fullUser"` lets the
     * user rotate mid-run and flip the condition; rotating mid-run does not flip the condition,
     * because the fingerprint was already built. That is precisely the case it labels wrongly.
     *
     * Known gap: the renderer only observes a capture it performed, so a target restored from a
     * saved project reads -1 rather than its original rotation. -1 is honest there; a 0 would not
     * be. Persisting this on `Fingerprint` is `IMPLEMENTATION.md` todo 0.7.
     */
    val captureRotationNeededDeg: Int = -1,
    /**
     * `(sensorOrientation - displayRotation*90 + 360) % 360` for *this tick* — how the device is
     * being held right now, during relocalization. -1 when not sampled.
     *
     * Secondary. It is **not** E0b's independent variable (see [captureRotationNeededDeg]), and
     * grouping by it is the mistake this pair of columns exists to prevent. It is logged because
     * the live path has frame handling of its own — the reloc feed is rotated by `cvRotateCode`
     * while camera intrinsics are read *unrotated* from `imageIntrinsics` — so a run where the
     * two columns disagree is the one worth looking at twice.
     */
    val liveRotationNeededDeg: Int = -1,
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
        "relocBackboneFeatures", "relocBackboneMatches", "relocBackboneInliers",
        "corrobPredicted", "corrobMatched", "corrobLoneSkips", "searchRadiusPx", "relocReprojPx",
        "inlierSpread", "corrobGate", "growOutcome", "fusionState", "fusionCorrectionMm",
        "captureRotationNeededDeg", "liveRotationNeededDeg",
    )

    const val NOT_SAMPLED = -1

    /**
     * The same sentinel for the pixel-valued columns. Spelled as its own constant rather than a bare
     * `-1f` at each use site so a reader can see that [searchRadiusPx][EvalSample.searchRadiusPx]
     * and [relocReprojPx][EvalSample.relocReprojPx] are absent for the same reason as every Int
     * column beside them, and so a later change to the convention has one place to change.
     */
    const val NOT_SAMPLED_PX = -1f

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
            s.relocBackboneFeatures.toString(), s.relocBackboneMatches.toString(),
            s.relocBackboneInliers.toString(),
            s.corrobPredicted.toString(), s.corrobMatched.toString(),
            s.corrobLoneSkips.toString(),
            s.searchRadiusPx.toString(), s.relocReprojPx.toString(),
            s.inlierSpread.toString(),
            s.corrobGate.toString(), s.growOutcome.toString(),
            s.fusionState.toString(), s.fusionCorrectionMm.toString(),
            s.captureRotationNeededDeg.toString(), s.liveRotationNeededDeg.toString(),
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
