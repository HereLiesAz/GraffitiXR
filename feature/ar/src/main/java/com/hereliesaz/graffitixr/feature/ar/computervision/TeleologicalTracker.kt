// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/computervision/TeleologicalTracker.kt
package com.hereliesaz.graffitixr.feature.ar.computervision

import com.hereliesaz.graffitixr.common.model.Fingerprint
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinator for the native C++ Teleological Relocalization engine.
 * 
 * The heavy OpenCV Perspective-n-Point (PnP) solving has been migrated 
 * out of Kotlin and into MobileGS.cpp for zero-copy performance. 
 * This class now serves strictly to pass the saved project Fingerprint 
 * across the JNI boundary so the native engine can find it.
 */
@Singleton
class TeleologicalTracker @Inject constructor(
    private val slamManager: SlamManager
) {
    /**
     * Injects the saved target features into the C++ engine.
     * When ARCore loses tracking, the C++ engine will independently attempt
     * to match live camera frames against this fingerprint to correct drift.
     */
    fun registerTarget(fingerprint: Fingerprint) {
        slamManager.setTargetFingerprint(
            fingerprint.descriptorsData,
            fingerprint.descriptorsRows,
            fingerprint.descriptorsCols,
            fingerprint.descriptorsType,
            fingerprint.points3d.toFloatArray()
        )
    }

    /**
     * Manual override for the anchor transform, if user performs an explicit
     * "Snap to Target" gesture in the UI.
     */
    fun forceAnchorTransform(matrix: FloatArray) {
        slamManager.updateAnchorTransform(matrix)
    }
}