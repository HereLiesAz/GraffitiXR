package com.hereliesaz.graffitixr.feature.ar.anchor

import android.opengl.Matrix
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

    // The master artwork pose in world space, set when the first anchor is established.
    private var masterArtworkPose: Pose? = null

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
                else Matrix.setIdentityM(outMatrix, 0)
            }
            return
        }

        // Weighted Average of translation and SLERP for rotation
        var totalX = 0f; var totalY = 0f; var totalZ = 0f
        val quats = mutableListOf<FloatArray>()
        val weights = mutableListOf<Float>()

        for (ca in tracking) {
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

    fun getActiveAnchorCount(): Int = synchronized(this) {
        consensusAnchors.count { it.anchor.trackingState == TrackingState.TRACKING }
    }
}
