package com.hereliesaz.graffitixr.feature.ar.anticipation

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.hereliesaz.graffitixr.common.model.AnticipationSettings

/** Battery and heat pressure, each 0 (none) to 1 (full); see [AnticipationSettings]. */
internal data class DevicePressure(val battery: Float, val heat: Float) {
    companion object {
        val NONE = DevicePressure(0f, 0f)
    }
}

/** Reads [DevicePressure]; called at most every couple of seconds. */
internal fun interface DevicePressureSource {
    fun read(): DevicePressure
}

/**
 * The device's own pressure: battery from the sticky battery broadcast (none while charging), heat
 * from [PowerManager.getThermalHeadroom] (API 30+; Android throttles at 1), else
 * [PowerManager.getCurrentThermalStatus] (API 29: light ¼ … critical 1), else none.
 */
internal class AndroidDevicePressure(context: Context) : DevicePressureSource {
    private val app = context.applicationContext
    private val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager

    override fun read(): DevicePressure = DevicePressure(battery(), heat())

    private fun battery(): Float {
        val status = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            ?: return 0f
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (plugged || level < 0 || scale <= 0) return 0f
        return (1f - level.toFloat() / scale).coerceIn(0f, 1f)
    }

    private fun heat(): Float {
        val pm = power ?: return 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val headroom = runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN)
            if (headroom.isFinite()) return headroom.coerceIn(0f, 1f)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return (pm.currentThermalStatus / 4f).coerceIn(0f, 1f)
        }
        return 0f
    }
}

/**
 * A throttled subset of a resolution's views: the [shells] nearest half-spheres, and on them every
 * [circleStep]th circle, every [meridianStep]th meridian (from 0, the page's right) and every
 * [rollStep]th roll (from upright). Close-ups are where the artist works and where the previews
 * matter most, so throttling cuts the far half-spheres first, outermost in, and only then thins the
 * nearest one's warps; its aim points (its coverage of the wall) are never thinned. Only the nested
 * subsets of [chain] are used, so lowering builds nothing and raising again reuses what was built.
 * [NONE] keeps nothing.
 */
internal data class AnticipationDecimation(val shells: Int, val circleStep: Int, val meridianStep: Int, val rollStep: Int) {

    fun keeps(site: HalfSphere.Site, max: AnticipationSettings): Boolean {
        if (this == NONE) return false
        if (site.shell >= shells) return false
        if (site.circle != 0 && site.circle % circleStep != 0) return false
        if (site.circle != 0 && site.meridian % meridianStep != 0) return false
        if (site.roll % rollStep != 0) return false
        return true
    }

    /** How many of [max]'s views this subset keeps. */
    fun views(max: AnticipationSettings): Int {
        if (this == NONE || max.distance == 0) return 0
        val aims = (0 until minOf(shells, max.distance)).sumOf { max.aimsPerSide(it).let { k -> k * k } }
        val circles = (1..max.longitude).count { it % circleStep == 0 }
        val meridians = (0 until max.latitude).count { it % meridianStep == 0 }
        val rolls = (0 until max.roll).count { it % rollStep == 0 }
        return aims * (1 + circles * meridians) * rolls
    }

    companion object {
        val FULL = AnticipationDecimation(Int.MAX_VALUE, 1, 1, 1)
        val NONE = AnticipationDecimation(0, 0, 0, 0)

        /**
         * The throttled subsets of [max], from [FULL] down to [NONE], each a subset of the one before:
         * first the outermost half-sphere goes, then the next, down to the nearest; then the nearest
         * thins, each step doubling one step (meridian, circle or roll), the one that keeps the most
         * views, so it thins evenly; its upright poles (every aim) are the last views standing.
         */
        fun chain(max: AnticipationSettings): List<AnticipationDecimation> {
            if (max.maxViews == 0) return listOf(NONE)
            val out = arrayListOf(FULL)
            for (kept in max.distance - 1 downTo 1) out += FULL.copy(shells = kept)
            var d = out.last()
            while (true) {
                val views = d.views(max)
                val next = listOf(
                    d.copy(meridianStep = d.meridianStep * 2),
                    d.copy(circleStep = d.circleStep * 2),
                    d.copy(rollStep = d.rollStep * 2),
                ).filter { it.views(max) < views }.maxByOrNull { it.views(max) } ?: break
                d = next
                out += d
            }
            out += NONE
            return out
        }

        /** The first of [chain] with at most [factor] × [max]'s views ([NONE] when not one fits). */
        fun forFactor(max: AnticipationSettings, factor: Float): AnticipationDecimation {
            if (factor >= 1f) return if (max.maxViews == 0) NONE else FULL
            val budget = factor * max.maxViews
            return chain(max).first { it == NONE || it.views(max) <= budget }
        }
    }
}

/** What is anticipated: the [max] resolution, the [decimation] of it in force, and why. */
internal data class AnticipationStatus(
    val max: AnticipationSettings,
    val decimation: AnticipationDecimation,
    val factor: Float,
    val batteryPressure: Float,
    val heatPressure: Float,
    val computePressure: Float,
) {
    /** Views per page in force. */
    val views: Int get() = decimation.views(max)
}

/**
 * Throttles the anticipation resolution from battery, heat and compute pressure, by the weights in
 * [settings]. [onFrame] takes each frame's processing time; [update] re-reads the pressures every
 * [intervalMs] and picks the subset of the maximum that fits. A lower subset applies at once; a higher
 * one only after it has been wanted for [raiseAfterMs], so a pressure hovering at a step does not
 * rebuild and drop views. A settings change applies at the next [update].
 */
internal class AnticipationGovernor(
    settings: AnticipationSettings,
    @Volatile var source: DevicePressureSource?,
    private val intervalMs: Long = 2_000L,
    private val raiseAfterMs: Long = 10_000L,
) {
    @Volatile var settings: AnticipationSettings = settings.sanitized()
        set(value) {
            field = value.sanitized()
        }

    private var utilization = 0f
    private var lastFrameNs = Long.MIN_VALUE
    private var lastUpdateMs = Long.MIN_VALUE
    private var raiseSinceMs = Long.MIN_VALUE
    private var applied: AnticipationSettings? = null

    @Volatile var status: AnticipationStatus = AnticipationStatus(this.settings, AnticipationDecimation.FULL, 1f, 0f, 0f, 0f)
        private set

    /** One frame took [workMs] to process; [timestampNs] is the camera's, for the frame interval. */
    fun onFrame(workMs: Float, timestampNs: Long) {
        val last = lastFrameNs
        lastFrameNs = timestampNs
        if (last == Long.MIN_VALUE || timestampNs <= last) return
        val frameMs = (timestampNs - last) / 1e6f
        if (frameMs > 1_000f) return // a pause, not a frame interval
        utilization += 0.1f * (workMs / frameMs - utilization)
    }

    /** The status for now: re-read and re-chosen every [intervalMs], or at once when [settings] changed. */
    fun update(nowMs: Long): AnticipationStatus {
        val s = settings
        if (s != applied) {
            applied = s
            lastUpdateMs = Long.MIN_VALUE
            raiseSinceMs = Long.MIN_VALUE
            status = status.copy(max = s, decimation = AnticipationDecimation.forFactor(s, status.factor))
        }
        if (lastUpdateMs != Long.MIN_VALUE && nowMs - lastUpdateMs < intervalMs) return status
        lastUpdateMs = nowMs
        val device = source?.let { runCatching { it.read() }.getOrNull() } ?: DevicePressure.NONE
        val compute = ((utilization - 0.5f) / 0.5f).coerceIn(0f, 1f)
        val factor = (1f - s.batteryWeight * device.battery) * (1f - s.heatWeight * device.heat) * (1f - s.computeWeight * compute)
        val wanted = AnticipationDecimation.forFactor(s, factor)
        val current = status.decimation
        val chosen = when {
            wanted.views(s) <= current.views(s) -> { raiseSinceMs = Long.MIN_VALUE; wanted }
            raiseSinceMs == Long.MIN_VALUE -> { raiseSinceMs = nowMs; current }
            nowMs - raiseSinceMs >= raiseAfterMs -> { raiseSinceMs = Long.MIN_VALUE; wanted }
            else -> current
        }
        status = AnticipationStatus(s, chosen, factor, device.battery, device.heat, compute)
        return status
    }
}
