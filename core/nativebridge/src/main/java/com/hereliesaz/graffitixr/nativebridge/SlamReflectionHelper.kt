package com.hereliesaz.graffitixr.nativebridge

import android.content.Context

/**
 * Legacy helper class for the deprecated SphereSLAM engine.
 *
 * This class previously handled reflection-based initialization of the SLAM engine.
 * With the migration to the unified [MobileGS] engine and [SlamManager], this class
 * is now a no-op stub kept only to prevent compilation errors in legacy code paths.
 *
 * @deprecated Use [SlamManager] for all SLAM operations.
 */
@Deprecated("Use SlamManager instead.")
object SlamReflectionHelper {

    /**
     * Previously initialized the SphereSLAM engine.
     * Now a no-op.
     */
    fun init(context: Context) {
        // No-op
    }

    /**
     * Checks if the engine is initialized.
     * @return Always returns `false`.
     */
    fun isInitialized(): Boolean = false

    /**
     * Cleans up resources.
     * Now a no-op.
     */
    fun cleanup() {
        // No-op
    }
}
