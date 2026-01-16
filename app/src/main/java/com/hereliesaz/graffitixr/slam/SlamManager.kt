package com.hereliesaz.graffitixr.slam

import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Session
import com.google.ar.core.Session.FeatureMapQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the "Neural Scan" process (Cloud Anchor Hosting).
 * It talks to the ARCore oracle to determine if the world is real enough to save.
 */
class SlamManager {

    private val _mappingQuality = MutableStateFlow(FeatureMapQuality.INSUFFICIENT)
    val mappingQuality = _mappingQuality.asStateFlow()

    private val _isHosting = MutableStateFlow(false)
    val isHosting = _isHosting.asStateFlow()

    /**
     * Ask the oracle: "How good is the data for this pose?"
     */
    fun updateFeatureMapQuality(session: Session, cameraPose: com.google.ar.core.Pose) {
        try {
            val quality = session.estimateFeatureMapQualityForHosting(cameraPose)
            _mappingQuality.value = quality
        } catch (e: Exception) {
            // The oracle is silent (usually session is paused or not ready)
        }
    }

    /**
     * The Sacrifice: Upload the anchor to the cloud.
     */
    fun hostAnchor(session: Session, anchor: Anchor, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        _isHosting.value = true
        session.hostCloudAnchorAsync(anchor, 365) { cloudAnchorId, state ->
            _isHosting.value = false
            if (state == Anchor.CloudAnchorState.SUCCESS) {
                onSuccess(cloudAnchorId)
            } else {
                onError(state.toString())
            }
        }
    }

    fun reset() {
        _mappingQuality.value = FeatureMapQuality.INSUFFICIENT
        _isHosting.value = false
    }
}