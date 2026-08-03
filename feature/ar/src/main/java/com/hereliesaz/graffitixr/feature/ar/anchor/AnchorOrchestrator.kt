package com.hereliesaz.graffitixr.feature.ar.anchor

import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import timber.log.Timber

/**
 * Orchestrates a "Democratic Consensus" of AR anchors to maintain a stable
 * model matrix for artwork layers, even as the primary target moves off-screen.
 *
 * Thread-safety: [getConsensusMatrix]/[getActiveAnchorCount] run on the GL render thread while
 * [clear]/[setInitialAnchor]/[addSupportAnchor] are driven from the ViewModel/main thread, so every
 * access to the shared mutable state (`consensusAnchors`, `masterArtworkPose`, `lastGoodMatrix`,
 * `hasLastGood`) is guarded by `synchronized(this)`. The per-frame path snapshots under the lock and
 * does the (lock-free) math outside it, so contention is limited to the brief snapshot/store.
 */
class AnchorOrchestrator {

    private data class ConsensusAnchor(
        val anchor: Anchor,
        // The Pose of the artwork relative to this anchor.
        // calculated as: anchor.inverse() * artworkPose
        val artworkOffset: Pose
    )

    private val consensusAnchors = mutableListOf<ConsensusAnchor>()
    private val MAX_CONSENSUS_ANCHORS = 8

    /**
     * How far a support anchor's suggested artwork position may sit from the median before it is
     * excluded from the consensus, in metres.
     *
     * Half a metre is chosen to sit above honest disagreement and below the failure. Anchors on one
     * wall agree to within a few centimetres; ARCore drift between two anchors in the same room is
     * tens of centimetres at worst. The run this was written for went metres, and kept going.
     *
     * A prior, not a measurement — no experiment has swept it. Erring large is the safe direction:
     * too tight discards genuine anchors and reduces the consensus to one vote, which is the state
     * this class exists to improve on.
     */
    private val OUTLIER_RADIUS_M = 0.5f

    // The master artwork pose in world space, set when the first anchor is established.
    private var masterArtworkPose: Pose? = null

    // The primary anchor's translation at the instant it was established. Compared against its
    // live translation so a report can state, in metres, exactly how far the anchor itself has
    // moved — not the consensus average, not the rendered overlay, the raw ARCore Anchor. This is
    // the number that tells the next reader whether a "runs away" report is this mechanism again or
    // something new, instead of re-deriving it from a distance HUD and a stopwatch.
    private var establishedTranslation: FloatArray? = null

    /**
     * How far the primary anchor's own pose has moved since [setInitialAnchor], in metres, or -1 if
     * there is no established anchor or it has stopped tracking.
     */
    fun primaryAnchorDriftMeters(): Float = synchronized(this) {
        val e0 = establishedTranslation ?: return -1f
        val primary = consensusAnchors.firstOrNull() ?: return -1f
        if (primary.anchor.trackingState != TrackingState.TRACKING) return -1f
        val t = primary.anchor.pose.translation
        val dx = t[0] - e0[0]; val dy = t[1] - e0[1]; val dz = t[2] - e0[2]
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    // The last successfully-computed consensus matrix. Held during a (usually brief) tracking loss so
    // the overlay stays put on the wall instead of teleporting to the world origin — this is exactly
    // the "stays stuck even in your pocket" behaviour the app is built around.
    private val lastGoodMatrix = FloatArray(16)
    private var hasLastGood = false

    /**
     * Resets the orchestrator, typically on session clear or project load.
     */
    fun clear() {
        synchronized(this) {
            consensusAnchors.forEach { it.anchor.detach() }
            consensusAnchors.clear()
            masterArtworkPose = null
            establishedTranslation = null
            hasLastGood = false
        }
    }

    /**
     * Establishes the initial artwork pose based on the primary target anchor.
     */
    fun setInitialAnchor(anchor: Anchor) {
        synchronized(this) {
            // clear() re-enters this monitor (reentrant), keeping the reset+seed atomic.
            clear()
            masterArtworkPose = anchor.pose
            establishedTranslation = anchor.pose.translation
            consensusAnchors.add(ConsensusAnchor(anchor, Pose.IDENTITY))
        }
        Timber.d("Initial consensus anchor established at ${anchor.pose}")
    }

    /**
     * Promotes a world-space point to a support anchor.
     */
    fun addSupportAnchor(session: Session, worldPose: Pose) {
        synchronized(this) {
            if (consensusAnchors.size >= MAX_CONSENSUS_ANCHORS) return
            val master = masterArtworkPose ?: return

            val anchor = session.createAnchor(worldPose)
            // offset = anchor.inverse() * master
            val offset = worldPose.inverse().compose(master)

            consensusAnchors.add(ConsensusAnchor(anchor, offset))
            Timber.d("Support anchor added. Total consensus anchors: ${consensusAnchors.size}")
        }
    }

    /**
     * Computes the "Consensus Transform" by weighted-averaging the suggestions
     * from all currently tracking anchors.
     *
     * suggests[i] = anchor_i.pose * offset_i
     */
    fun getConsensusMatrix(outMatrix: FloatArray) {
        // Snapshot the tracking set (and the anchor poses we need) under the lock, then do the math
        // lock-free. Filtering a plain MutableList concurrently with clear() would otherwise CME.
        val tracking = synchronized(this) {
            consensusAnchors.filter { it.anchor.trackingState == TrackingState.TRACKING }
        }

        if (tracking.isEmpty()) {
            // No anchor is tracking this frame. HOLD the last good world matrix rather than writing
            // identity, which would snap the artwork overlay to the world origin on every dropped
            // frame. Only fall back to identity before any consensus has ever been computed.
            synchronized(this) {
                if (hasLastGood) System.arraycopy(lastGoodMatrix, 0, outMatrix, 0, 16)
                // Hand-written instead of Matrix.setIdentityM: the unit tests run with
                // `unitTests.isReturnDefaultValues = true`, under which setIdentityM is a no-op stub
                // that leaves outMatrix all zeros without throwing — so this fallback could never be
                // asserted. Same layout as PoseFusion.identity().
                else identity().copyInto(outMatrix)
            }
            return
        }

        // Every tracking anchor's vote for where the artwork is.
        val suggestions = tracking.map { it.anchor.pose.compose(it.artworkOffset) }

        // Throw out the anchors that disagree with the rest before averaging.
        //
        // Without this, one support anchor is enough to take the artwork with it. ARCore anchors
        // drift and occasionally jump — in a dim room with weak tracking, routinely — and a weighted
        // MEAN has no defence against that: a single outlier moves the result in proportion to how
        // wrong it is. Worse, `weight = 1/(1+dist)` is computed from the offset captured when the
        // anchor was created, so an anchor that has since flown across the room keeps whatever
        // authority it had when it was still trustworthy.
        //
        // A device run watched the artwork recede 4.6 → 4.8 → 5.6 → 7.2 → 26.3 ft over four seconds
        // while relocalization held a 97% inlier ratio at 2px reprojection and fusion was off — so
        // nothing downstream of this function was involved. The overlay "very literally ran away".
        //
        // The median is the right centre here precisely because it cannot be dragged: half the votes
        // would have to be wrong before it moves. Anchors further than [OUTLIER_RADIUS_M] from it
        // are excluded from the average entirely — they are not consensus, they are the thing
        // consensus exists to survive.
        val medianPos = floatArrayOf(
            medianOf(suggestions.map { it.tx() }),
            medianOf(suggestions.map { it.ty() }),
            medianOf(suggestions.map { it.tz() }),
        )
        val kept = tracking.filterIndexed { i, _ ->
            val s = suggestions[i]
            val dx = s.tx() - medianPos[0]
            val dy = s.ty() - medianPos[1]
            val dz = s.tz() - medianPos[2]
            dx * dx + dy * dy + dz * dz <= OUTLIER_RADIUS_M * OUTLIER_RADIUS_M
        }.ifEmpty {
            // Cannot happen with a real median — the median element is always within 0 of itself —
            // but an empty list here would divide by a zero weight sum and write NaNs into the
            // artwork matrix, which is a far worse failure than keeping everything.
            tracking
        }

        // Weighted Average of translation and SLERP for rotation
        var totalX = 0f; var totalY = 0f; var totalZ = 0f
        val quats = mutableListOf<FloatArray>()
        val weights = mutableListOf<Float>()

        for (ca in kept) {
            val suggestion = ca.anchor.pose.compose(ca.artworkOffset)

            // Proximity weight: anchors closer to the artwork (smaller stored offset) are more
            // reliable for local precision, so weight by 1/(1+distance) rather than equally.
            val off = ca.artworkOffset
            val dist = kotlin.math.sqrt(off.tx() * off.tx() + off.ty() * off.ty() + off.tz() * off.tz())
            val weight = 1.0f / (1.0f + dist)

            totalX += suggestion.tx() * weight
            totalY += suggestion.ty() * weight
            totalZ += suggestion.tz() * weight

            quats.add(suggestion.rotationQuaternion)
            weights.add(weight)
        }

        val weightSum = weights.sum()
        val avgPos = floatArrayOf(totalX / weightSum, totalY / weightSum, totalZ / weightSum)

        // Simple linear interpolation of quaternions (normalized) for n-way blend
        // Note: For higher precision, use actual SO(3) averaging.
        val avgQuat = FloatArray(4)
        // Use a fixed reference (the first quaternion) for the hemisphere check. Seeding the
        // accumulator at {0,0,0,0} made the first dot product 0, so the flip was meaningless and
        // near-antipodal quats could cancel toward zero — forcing the identity fallback below and
        // silently discarding the blended orientation.
        val ref = quats.firstOrNull() ?: floatArrayOf(0f, 0f, 0f, 1f)
        for (i in 0 until quats.size) {
            val q = quats[i]
            val w = weights[i] / weightSum
            // Ensure quaternions are in the same hemisphere as the reference to avoid cancellation
            val dot = q[0] * ref[0] + q[1] * ref[1] + q[2] * ref[2] + q[3] * ref[3]
            val sign = if (dot >= 0) 1.0f else -1.0f

            avgQuat[0] += q[0] * w * sign
            avgQuat[1] += q[1] * w * sign
            avgQuat[2] += q[2] * w * sign
            avgQuat[3] += q[3] * w * sign
        }

        // Normalize the resulting blended quaternion
        val len = Math.sqrt((avgQuat[0]*avgQuat[0] + avgQuat[1]*avgQuat[1] + avgQuat[2]*avgQuat[2] + avgQuat[3]*avgQuat[3]).toDouble()).toFloat()
        if (len > 0) {
            avgQuat[0] /= len; avgQuat[1] /= len; avgQuat[2] /= len; avgQuat[3] /= len
        } else {
            avgQuat[3] = 1.0f // Identity
        }

        val finalPose = Pose(avgPos, avgQuat)
        finalPose.toMatrix(outMatrix, 0)
        // Remember this good matrix so a subsequent tracking dropout holds here instead of the origin.
        synchronized(this) {
            System.arraycopy(outMatrix, 0, lastGoodMatrix, 0, 16)
            hasLastGood = true
        }
    }

    private fun identity() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)

    /**
     * Median of a non-empty list, by the lower element on an even count.
     *
     * Deliberately not the mean of the two middles: this is used per-axis on a set of 3D positions,
     * and mixing an interpolated value into one axis while the others take real samples produces a
     * centre that no anchor ever voted for. Picking an actual sample keeps the reference point on
     * the manifold of things the anchors actually said.
     */
    private fun medianOf(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) / 2]
    }

    fun getActiveAnchorCount(): Int = synchronized(this) {
        consensusAnchors.count { it.anchor.trackingState == TrackingState.TRACKING }
    }
}
