package com.hereliesaz.graffitixr.feature.ar.eval

import android.content.Context
import android.os.BatteryManager
import com.hereliesaz.graffitixr.common.model.EvalLiveMetrics
import java.io.File

/**
 * Stateful per-session collector. Call [onTick] from the GL loop (throttled). It computes metrics
 * from the supplied poses using [EvalMetrics], samples battery/temp, and appends CSV rows to a log
 * file in the app files dir. Enabled only in dev/eval mode — must be cheap and gated by the caller.
 */
class DriftCostProbe(
    private val context: Context,
    private val deviceClass: String, // "dual" or "mono"
    private val nowMs: () -> Long,
) {
    private val recentTranslations = ArrayDeque<FloatArray>() // for jitter, last N stationary samples
    private val jitterWindow = 30
    private var usableFrames = 0
    private var totalFrames = 0
    private var lossMs: Long? = null
    private var relockMs: Long? = null
    private var logFile: File? = null

    /**
     * `EVALUATION.md` §3.1 — the most recent frame timestamp in milliseconds, or -1 before the first
     * tick. Held so the two stamps taken OUTSIDE [onTick] can also read the frame clock.
     *
     * `markTrackingLoss` is called from an overlay button, between ticks; there is no frame in its
     * hand. Using the last one the probe saw is the right reading, because what §3.1 is buying is
     * that two replays of one recording produce the same numbers — and the last frame before a
     * button press is the same frame in both replays, whereas the wall clock is not the same time.
     */
    @Volatile private var lastFrameMs: Long = -1L

    @Volatile var lastMetrics = EvalLiveMetrics()

    private val batteryManager by lazy {
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }

    /**
     * Begin a run. Pass [identity] so the CSV gets its run-identity sidecar — `EVALUATION.md` §3.2:
     * a CSV without one is not evidence, because the parameter values, build and determinism
     * settings that produced it are not recoverable from the numbers afterwards.
     *
     * [identity] is nullable only so ad-hoc debugging can still start a probe; any run whose numbers
     * are meant to be compared against another run must supply it.
     */
    fun start(identity: EvalRunIdentity? = null): File {
        val dir = File(context.filesDir, "eval").apply { mkdirs() }
        val f = File(dir, "eval_${deviceClass}_${nowMs()}.csv")
        f.writeText(EvalSampleLog.CSV_HEADER + "\n")
        if (identity != null) {
            File(dir, identity.sidecarNameFor(f.name)).writeText(identity.toJson())
        }
        logFile = f
        usableFrames = 0; totalFrames = 0; lossMs = null; relockMs = null
        // Reset with the rest of the run state, or the first tick of run two would be stamped with
        // the last frame of run one.
        lastFrameMs = -1L
        recentTranslations.clear()
        return f
    }

    fun stop() { logFile = null }

    /**
     * Marks an induced tracking loss (overlay button) so the next re-lock yields recovery time.
     *
     * Stamped on the FRAME clock, not the wall clock. §3.1 item 3 names only `tsMs`, and this is a
     * deliberate widening rather than an oversight: `recoveryMs` is `relock - loss`, so leaving both
     * ends on the wall clock would leave recovery time measuring scheduling — the same defect the
     * item describes, one field over, in a number `EVALUATION.md` reports per mechanism.
     */
    fun markTrackingLoss() { lossMs = evalClockMs(); relockMs = null }

    /**
     * The frame clock when one has been seen, the wall clock otherwise. See [EvalClock.stampMs] for
     * why the fallback exists rather than a refusal.
     */
    private fun evalClockMs(): Long =
        EvalClock.stampMs(
            if (lastFrameMs >= 0L) lastFrameMs * 1_000_000L else EvalClock.NOT_SAMPLED_NS,
            nowMs,
        )

    /**
     * @param candidatePose 4x4 column-major pose of the mechanism under test (or the active fused pose)
     * @param truthPose     4x4 mark-PnP pose, or null when marks are not visible
     * @param isTracking    whether the mechanism currently has a usable lock (for recovery/availability)
     * @param stageMs        native stage timings from SlamManager.getStageTimings()
     * @param cpuPct         caller-sampled CPU percent (or -1 if unavailable)
     * @param reloc          last relocalization diagnostics, or null when not sampled. Logged so a
     *   null relocalization becomes a diagnosis rather than an absence: NO_FEATURES and FEW_INLIERS
     *   call for opposite fixes and the aggregate error number cannot tell them apart.
     * @param corrob        last corroboration diagnostics, or null when not sampled. Read from
     *   `SlamManager.getCorroborationDiagnostics()`, which is a separate call from the reloc
     *   diagnostics only because these two readings are floats; pass it alongside [reloc] so both
     *   describe the same tick rather than two samples taken at different times.
     * @param captureRotationNeededDeg `rotationNeeded` at the moment the ACTIVE TARGET was
     *   captured, or -1 if unknown. **E0b's independent variable** — the frame mismatch is baked
     *   into the fingerprint at capture, so this and not [liveRotationNeededDeg] is what results
     *   must be grouped by. See [EvalSample.captureRotationNeededDeg].
     * @param liveRotationNeededDeg `rotationNeeded` for this tick — how the device is held right
     *   now. Secondary; see [EvalSample.liveRotationNeededDeg].
     * @param frameTimestampNs ARCore's `Frame.getTimestamp()` for this frame, or
     *   [EvalClock.NOT_SAMPLED_NS]. During playback ARCore replays the RECORDED value, which is what
     *   makes two replays of one file align row-for-row — see [EvalClock].
     */
    fun onTick(
        candidatePose: FloatArray,
        truthPose: FloatArray?,
        isTracking: Boolean,
        stageMs: FloatArray,
        cpuPct: Float,
        reloc: com.hereliesaz.graffitixr.common.model.RelocDiagnostics? = null,
        corrob: com.hereliesaz.graffitixr.common.model.CorroborationDiagnostics? = null,
        fusion: com.hereliesaz.graffitixr.common.model.FusionDiagnostics? = null,
        captureRotationNeededDeg: Int = EvalSampleLog.NOT_SAMPLED,
        liveRotationNeededDeg: Int = EvalSampleLog.NOT_SAMPLED,
        frameTimestampNs: Long = EvalClock.NOT_SAMPLED_NS,
    ) {
        val file = logFile ?: return
        // Recorded BEFORE the early return below would have mattered and before anything reads the
        // clock, so every stamp in this tick — the row, and a relock detected inside it — comes from
        // the same frame rather than from this frame and the previous one.
        val stampMs = EvalClock.stampMs(frameTimestampNs, nowMs)
        if (frameTimestampNs > 0L) lastFrameMs = stampMs
        totalFrames++
        if (isTracking) usableFrames++

        // Recovery: first re-lock after an induced loss. On the frame clock, matching
        // markTrackingLoss — a recovery measured with one end on each clock is not a duration.
        if (lossMs != null && relockMs == null && isTracking) relockMs = stampMs

        // Jitter window (translation column of the candidate pose).
        val t = floatArrayOf(candidatePose[12], candidatePose[13], candidatePose[14])
        recentTranslations.addLast(t)
        while (recentTranslations.size > jitterWindow) recentTranslations.removeFirst()

        val marksVisible = truthPose != null
        val err = if (truthPose != null) EvalMetrics.poseError(candidatePose, truthPose) else null

        val sample = EvalSample(
            // EVALUATION.md 3.1 item 3: the RECORDING's frame timestamp, so two replays of one file
            // produce rows that align frame-for-frame and can be subtracted. Falls back to the wall
            // clock only when there is no frame stamp — a live session, which is not being aligned
            // against anything.
            tsMs = stampMs,
            deviceClass = deviceClass,
            marksVisible = marksVisible,
            errMm = err?.translationMm ?: -1f,
            errDeg = err?.rotationDeg ?: -1f,
            jitterMm = EvalMetrics.jitterMm(recentTranslations.toList()),
            availability = EvalMetrics.availability(usableFrames, totalFrames),
            stageMs = stageMs,
            cpuPct = cpuPct,
            batteryMa = sampleBatteryMa(),
            tempC = -1f, // wired from caller's thermal sample if available; -1 = not sampled
            nativeHeapKb = android.os.Debug.getNativeHeapAllocatedSize() / 1024L,
            // -1 throughout when not sampled. Zero matches and zero detected are both legitimate
            // readings, so they cannot double as the "no data" marker.
            relocReject = reloc?.reject?.ordinal ?: EvalSampleLog.NOT_SAMPLED,
            relocMatches = reloc?.matches ?: EvalSampleLog.NOT_SAMPLED,
            relocInliers = reloc?.inliers ?: EvalSampleLog.NOT_SAMPLED,
            relocDetected = reloc?.detected ?: EvalSampleLog.NOT_SAMPLED,
            relocObliquityDeg = reloc?.obliquityDeg ?: EvalSampleLog.NOT_SAMPLED,
            relocRectifiedCorr = reloc?.rectifiedCorrespondences ?: EvalSampleLog.NOT_SAMPLED,
            // Already -1 when native has not measured them, so the elvis only covers "no
            // diagnostics read this tick at all". Both routes must land on -1, never 0: an empty
            // F_out is a real result and is the one this column exists to catch.
            relocBackboneFeatures = reloc?.backboneFeatures ?: EvalSampleLog.NOT_SAMPLED,
            relocBackboneMatches = reloc?.backboneMatches ?: EvalSampleLog.NOT_SAMPLED,
            relocBackboneInliers = reloc?.backboneInliers ?: EvalSampleLog.NOT_SAMPLED,
            // Same two routes as the backbone counts, and the same rule: native already reports -1
            // for a quantity it has not measured, so the elvis only covers "no diagnostics were
            // read this tick at all". Neither route may land on 0. A zero predicted count is the
            // artist looking away from the design — a real state 4.8 has to keep separate from a
            // gate that never fired — and a zero reprojection error is what a perfect lock reports,
            // so writing 0 for absent data would file the best rows and the empty rows together.
            corrobPredicted = reloc?.corrobPredicted ?: EvalSampleLog.NOT_SAMPLED,
            corrobMatched = reloc?.corrobMatched ?: EvalSampleLog.NOT_SAMPLED,
            corrobLoneSkips = reloc?.corrobLoneSkips ?: EvalSampleLog.NOT_SAMPLED,
            searchRadiusPx = corrob?.searchRadiusPx ?: EvalSampleLog.NOT_SAMPLED_PX,
            relocReprojPx = corrob?.relocReprojPx ?: EvalSampleLog.NOT_SAMPLED_PX,
            inlierSpread = corrob?.inlierSpread ?: EvalSampleLog.NOT_SAMPLED_PX,
            // Ordinals, so the CSV stays numeric and a consumer maps them back through the enums.
            // -1 only for "no diagnostics this tick" — the enums have their own NOT_RUN entries,
            // which mean something different and must survive the round trip.
            corrobGate = reloc?.corrobGate?.ordinal ?: EvalSampleLog.NOT_SAMPLED,
            growOutcome = reloc?.growOutcome?.ordinal ?: EvalSampleLog.NOT_SAMPLED,
            fusionState = fusion?.state?.ordinal ?: EvalSampleLog.NOT_SAMPLED,
            fusionCorrectionMm = fusion?.correctionMm ?: EvalSampleLog.NOT_SAMPLED_PX,
            captureRotationNeededDeg = captureRotationNeededDeg,
            liveRotationNeededDeg = liveRotationNeededDeg,
        )
        file.appendText(EvalSampleLog.toCsvRow(sample) + "\n")

        lastMetrics = EvalLiveMetrics(
            errMm = sample.errMm, errDeg = sample.errDeg, jitterMm = sample.jitterMm,
            availability = sample.availability, recoveryMs = recoveryMs(),
            stageMs = sample.stageMs, batteryMa = sample.batteryMa,
        )
    }

    private fun sampleBatteryMa(): Float {
        // CURRENT_NOW is µA on most devices; convert to mA. Sign convention is device-dependent.
        val micro = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return if (micro == Int.MIN_VALUE) 0f else micro / 1000f
    }

    fun recoveryMs(): Long? = lossMs?.let { EvalMetrics.recoveryMs(it, relockMs) }
}
