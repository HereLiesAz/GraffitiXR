package com.hereliesaz.graffitixr.feature.ar.eval

import com.hereliesaz.graffitixr.common.model.CorrobGate
import com.hereliesaz.graffitixr.common.model.CorroborationDiagnostics
import com.hereliesaz.graffitixr.common.model.FusionDiagnostics
import com.hereliesaz.graffitixr.common.model.FusionState
import com.hereliesaz.graffitixr.common.model.GrowOutcome
import com.hereliesaz.graffitixr.common.model.RelocDiagnostics
import com.hereliesaz.graffitixr.common.model.RelocReject

/**
 * Decides when a frame is worth photographing.
 *
 * [DiagnosticRecorder] answers "did this ever happen" over a window. It cannot answer the question a
 * picture answers: **what was the camera looking at when it happened.** A lock that dies every time
 * the artist pans onto a blank rendered wall, and a lock that dies for no visible reason, produce
 * identical diagnostic rows and completely different fixes. So this watches the same tick stream for
 * transitions where the *scene* is the missing evidence, and asks for a screenshot.
 *
 * ## Transitions, never states
 *
 * Every trigger below is an edge. A state predicate at a 15 Hz tick would fire fifteen times a
 * second for as long as the state held, and a hundred identical PNGs of a stationary camera are not
 * fifteen times the evidence of one. The interesting instant is always the one where something
 * changed.
 *
 * ## Why it is pure
 *
 * `nowMs` is a parameter and nothing here touches Android. The rate limiting is the part most likely
 * to be wrong — a limiter that quietly refuses everything looks exactly like a watcher whose
 * triggers never fired, which is the same silent-no-op failure this whole diagnostic effort exists
 * to end — so it has to be testable without a device.
 */
class DiagnosticWatcher(
    private val globalCooldownMs: Long = GLOBAL_COOLDOWN_MS,
    private val repeatCooldownMs: Long = REPEAT_COOLDOWN_MS,
    private val maxCaptures: Int = MAX_CAPTURES,
    private val spikeMm: Float = SPIKE_MM,
) {

    private var prevReject: RelocReject? = null
    private var prevFusion: FusionState? = null
    private var prevGate: CorrobGate? = null
    private var prevCorrectionMm: Float = NOT_MEASURED

    private val firedOnce = HashSet<CaptureTrigger>()
    private val lastFiredMs = HashMap<CaptureTrigger, Long>()

    /**
     * Null until something fires, rather than a `Long.MIN_VALUE` "long ago".
     *
     * The sentinel version of this shipped for exactly one test run: `nowMs - Long.MIN_VALUE`
     * overflows to a negative number, which compares below every cooldown, so the limiter refused
     * **every capture for the whole session** — and refused them silently, producing reports that
     * said no watched transition had occurred. The failure this class was written to prevent, in the
     * class itself.
     */
    private var lastAnyMs: Long? = null
    private var requested = 0

    /** How many captures the budget still allows. Surfaced so the report can say the cap was hit. */
    @get:Synchronized
    val requestCount: Int get() = requested

    @get:Synchronized
    val atCap: Boolean get() = requested >= maxCaptures

    /**
     * One tick. Returns the trigger to photograph, or null.
     *
     * **At most one per tick, by design.** `PixelCopy` plus a PNG encode is not free, and two
     * triggers on the same tick would produce two byte-identical images of one frame. When several
     * fire together the rarest wins — see [CaptureTrigger]'s declaration order.
     */
    @Synchronized
    fun onTick(
        nowMs: Long,
        reloc: RelocDiagnostics,
        corrob: CorroborationDiagnostics,
        fusion: FusionDiagnostics,
    ): CaptureTrigger? {
        val candidates = detect(reloc, fusion)
        // Advance the edge state BEFORE the rate-limit decision, and unconditionally.
        //
        // If a suppressed transition left `prev` untouched, the same edge would be re-detected on
        // every subsequent tick until it finally got through — turning a one-off cooldown into a
        // standing request that fires the instant the cooldown lapses, on a frame that has nothing
        // to do with the transition. A suppressed edge is DROPPED, permanently. That is the honest
        // behaviour: the alternative photographs the wrong moment and labels it the right one.
        prevReject = reloc.reject
        prevFusion = fusion.state
        prevGate = reloc.corrobGate
        if (fusion.correctionMm >= 0f) prevCorrectionMm = fusion.correctionMm

        if (requested >= maxCaptures) return null
        val sinceAny = lastAnyMs
        for (t in candidates) {
            if (t.once && t in firedOnce) continue
            if (sinceAny != null && nowMs - sinceAny < globalCooldownMs) return null
            val last = lastFiredMs[t]
            if (!t.once && last != null && nowMs - last < repeatCooldownMs) continue
            firedOnce.add(t)
            lastFiredMs[t] = nowMs
            lastAnyMs = nowMs
            requested++
            return t
        }
        return null
    }

    @Synchronized
    fun reset() {
        prevReject = null
        prevFusion = null
        prevGate = null
        prevCorrectionMm = NOT_MEASURED
        firedOnce.clear()
        lastFiredMs.clear()
        lastAnyMs = null
        requested = 0
    }

    /**
     * Which edges this tick crossed, rarest first.
     *
     * The first tick after construction (or [reset]) crosses nothing: `prev` is null and every
     * comparison below requires a previous value. Otherwise entering AR would photograph whatever
     * arbitrary state the very first sample happened to hold, and label it a transition.
     */
    private fun detect(reloc: RelocDiagnostics, fusion: FusionDiagnostics): List<CaptureTrigger> {
        val out = ArrayList<CaptureTrigger>(3)
        val pReject = prevReject
        val pFusion = prevFusion
        val pGate = prevGate

        if (reloc.growOutcome == GrowOutcome.PROMOTED) out.add(CaptureTrigger.PROMOTED)

        if (pFusion != null && pFusion != FusionState.NO_CAPTURE_POSE &&
            fusion.state == FusionState.NO_CAPTURE_POSE
        ) {
            out.add(CaptureTrigger.NO_CAPTURE_POSE)
        }

        // A rise past the threshold, not a level above it. A correction that settles large and stays
        // large is one event; sampling it as a state would photograph it until the cooldown starved
        // everything else.
        if (fusion.correctionMm >= spikeMm && prevCorrectionMm >= 0f && prevCorrectionMm < spikeMm) {
            out.add(CaptureTrigger.CORRECTION_SPIKE)
        }

        if (pFusion != null && pFusion != FusionState.COLD_SNAP &&
            fusion.state == FusionState.COLD_SNAP
        ) {
            out.add(CaptureTrigger.COLD_SNAP)
        }

        if (pGate != null && pGate != CorrobGate.GATED && reloc.corrobGate == CorrobGate.GATED) {
            out.add(CaptureTrigger.CORROBORATION_GATED)
        }

        if (pReject != null && pReject == RelocReject.OK && reloc.reject != RelocReject.OK &&
            reloc.reject != RelocReject.DISABLED
        ) {
            out.add(CaptureTrigger.LOCK_LOST)
        }

        if (pReject != null && pReject != RelocReject.OK && reloc.reject == RelocReject.OK) {
            out.add(CaptureTrigger.LOCK_ACQUIRED)
        }

        // The `if`s above happen to run in declaration order, but the priority claim must not depend
        // on that surviving an edit — a reordered block would silently change which trigger wins.
        out.sortBy { it.ordinal }
        return out
    }

    companion object {
        /**
         * No two captures within 3s of each other, whatever fired them. Two transitions a frame
         * apart are one event with two names, and the second picture is the first picture.
         */
        const val GLOBAL_COOLDOWN_MS = 3_000L

        /**
         * A repeatable trigger waits 15s before it may fire again. Lock loss in particular oscillates
         * — a marginal wall drops and re-acquires several times a second, and without this the budget
         * below would be spent inside two seconds on one flapping edge.
         */
        const val REPEAT_COOLDOWN_MS = 15_000L

        /**
         * Hard ceiling on captures per session. A full-resolution PNG is a few megabytes; this is a
         * diagnostic aid on a user's phone, not a dashcam.
         */
        const val MAX_CAPTURES = 16

        /**
         * Correction magnitude, in millimetres, above which a jump is worth a picture.
         *
         * 150mm is set from what the failures look like, not from a tolerance: a healthy relock
         * corrects a few millimetres to a couple of centimetres, and the composition defect `0.9`
         * fixed produced corrections in the metres. Anything over about a hand's width is either a
         * genuine relock after real drift — in which case the picture shows the wall the artist
         * walked back to — or a frame error, and the two are told apart by whether the overlay in the
         * photograph is on the wall.
         */
        const val SPIKE_MM = 150f

        private const val NOT_MEASURED = -1f
    }
}

/**
 * Why a frame was photographed.
 *
 * **Declaration order is priority order**, rarest first, because [DiagnosticWatcher.onTick] returns
 * only the first. A promotion happens at most a handful of times in a session and a lock is acquired
 * constantly; on the tick where both occur, the promotion is the one that will not come round again.
 */
enum class CaptureTrigger(
    /** Fires at most once per session — the second picture of it adds nothing. */
    val once: Boolean,
    /** What the reader of the report should look for in the image. */
    val whatToLookFor: String,
) {
    /** Self-grow wrote new marks into the fingerprint. The one irreversible act in the engine. */
    PROMOTED(once = false, whatToLookFor = "which part of the wall got promoted into the map"),

    /**
     * Fusion hit `IMPLEMENTATION.md` 0.9 — a pre-Phase-2 fingerprint with no capture pose, so the
     * overlay is uncorrected and will drift with no other symptom.
     */
    NO_CAPTURE_POSE(once = true, whatToLookFor = "the overlay is on the raw ARCore backbone here"),

    /** The standing correction jumped past [DiagnosticWatcher.SPIKE_MM]. */
    CORRECTION_SPIKE(once = false, whatToLookFor = "is the overlay ON the wall after the jump"),

    /** A hard snap: first lock, or a relock that diverged past the cold thresholds. */
    COLD_SNAP(once = true, whatToLookFor = "where the overlay landed on the first hard snap"),

    /** Phase 4's spatially-gated corroboration ran for the first time. */
    CORROBORATION_GATED(once = true, whatToLookFor = "the design is in frame and being corroborated"),

    /** Relocalization stopped locking. The picture is the answer to "pointed at what?". */
    LOCK_LOST(once = false, whatToLookFor = "what the camera was pointed at when the lock died"),

    /** Relocalization started locking. The baseline the failures are read against. */
    LOCK_ACQUIRED(once = true, whatToLookFor = "what a working lock looks like on this wall"),
}
