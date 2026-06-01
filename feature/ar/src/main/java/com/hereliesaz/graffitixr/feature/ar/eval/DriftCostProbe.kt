package com.hereliesaz.graffitixr.feature.ar.eval

import android.content.Context
import android.os.BatteryManager
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

    private val batteryManager by lazy {
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }

    fun start(): File {
        val dir = File(context.filesDir, "eval").apply { mkdirs() }
        val f = File(dir, "eval_${deviceClass}_${nowMs()}.csv")
        f.writeText(EvalSampleLog.CSV_HEADER + "\n")
        logFile = f
        usableFrames = 0; totalFrames = 0; lossMs = null; relockMs = null
        recentTranslations.clear()
        return f
    }

    fun stop() { logFile = null }

    /** Marks an induced tracking loss (overlay button) so the next re-lock yields recovery time. */
    fun markTrackingLoss() { lossMs = nowMs(); relockMs = null }

    /**
     * @param candidatePose 4x4 column-major pose of the mechanism under test (or the active fused pose)
     * @param truthPose     4x4 mark-PnP pose, or null when marks are not visible
     * @param isTracking    whether the mechanism currently has a usable lock (for recovery/availability)
     * @param stageMs        native stage timings from SlamManager.getStageTimings()
     * @param cpuPct         caller-sampled CPU percent (or -1 if unavailable)
     */
    fun onTick(
        candidatePose: FloatArray,
        truthPose: FloatArray?,
        isTracking: Boolean,
        stageMs: FloatArray,
        cpuPct: Float,
    ) {
        val file = logFile ?: return
        totalFrames++
        if (isTracking) usableFrames++

        // Recovery: first re-lock after an induced loss.
        if (lossMs != null && relockMs == null && isTracking) relockMs = nowMs()

        // Jitter window (translation column of the candidate pose).
        val t = floatArrayOf(candidatePose[12], candidatePose[13], candidatePose[14])
        recentTranslations.addLast(t)
        while (recentTranslations.size > jitterWindow) recentTranslations.removeFirst()

        val marksVisible = truthPose != null
        val err = if (truthPose != null) EvalMetrics.poseError(candidatePose, truthPose) else null

        val sample = EvalSample(
            tsMs = nowMs(),
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
        )
        file.appendText(EvalSampleLog.toCsvRow(sample) + "\n")
    }

    private fun sampleBatteryMa(): Float {
        // CURRENT_NOW is µA on most devices; convert to mA. Sign convention is device-dependent.
        val micro = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return if (micro == Int.MIN_VALUE) 0f else micro / 1000f
    }

    fun recoveryMs(): Long? = lossMs?.let { EvalMetrics.recoveryMs(it, relockMs) }
}
