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

    /**
     * Resets the orchestrator, typically on session clear or project load.
     */
    fun clear() {
        consensusAnchors.forEach { it.anchor.detach() }
        consensusAnchors.clear()
        masterArtworkPose = null
    }

    /**
     * Establishes the initial artwork pose based on the primary target anchor.
     */
    fun setInitialAnchor(anchor: Anchor) {
        clear()
        masterArtworkPose = anchor.pose
        consensusAnchors.add(ConsensusAnchor(anchor, Pose.IDENTITY))
        Timber.d("Initial consensus anchor established at ${anchor.pose}")
    }

    /**
     * Promotes a world-space point to a support anchor.
     */
    fun addSupportAnchor(session: Session, worldPose: Pose) {
        if (consensusAnchors.size >= MAX_CONSENSUS_ANCHORS) return
        val master = masterArtworkPose ?: return
        
        val anchor = session.createAnchor(worldPose)
        // offset = anchor.inverse() * master
        val offset = worldPose.inverse().compose(master)
        
        consensusAnchors.add(ConsensusAnchor(anchor, offset))
        Timber.d("Support anchor added. Total consensus anchors: ${consensusAnchors.size}")
    }

    /**
     * Computes the "Consensus Transform" by weighted-averaging the suggestions
     * from all currently tracking anchors.
     *
     * suggests[i] = anchor_i.pose * offset_i
     */
    fun getConsensusMatrix(outMatrix: FloatArray) {
        val tracking = consensusAnchors.filter { it.anchor.trackingState == TrackingState.TRACKING }
        
        if (tracking.isEmpty()) {
            Matrix.setIdentityM(outMatrix, 0)
            return
        }

        // Weighted Average of translation and SLERP for rotation
        var totalX = 0f; var totalY = 0f; var totalZ = 0f
        val quats = mutableListOf<FloatArray>()
        val weights = mutableListOf<Float>()

        for (ca in tracking) {
            val suggestion = ca.anchor.pose.compose(ca.artworkOffset)
            
            // Weight based on proximity? Or just equal weight for now.
            // A simple distance-based weight (1/d) could improve local precision.
            val weight = 1.0f 
            
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
        for (i in 0 until quats.size) {
            val q = quats[i]
            val w = weights[i] / weightSum
            // Ensure quaternions are in the same hemisphere to avoid cancellation
            val dot = q[0] * avgQuat[0] + q[1] * avgQuat[1] + q[2] * avgQuat[2] + q[3] * avgQuat[3]
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
    }

    fun getActiveAnchorCount(): Int = consensusAnchors.count { it.anchor.trackingState == TrackingState.TRACKING }
}
