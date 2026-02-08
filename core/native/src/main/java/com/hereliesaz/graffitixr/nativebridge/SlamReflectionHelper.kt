package com.hereliesaz.graffitixr.nativebridge

import android.content.Context

/**
 * Legacy helper for SphereSLAM.
 * Stubbed out as we have migrated to MobileGS.
 * This file is kept to prevent breakage in legacy calls but performs no operations.
 */
object SlamReflectionHelper {

    fun init(context: Context) {
        // No-op
    }

    fun isInitialized(): Boolean = false

    fun cleanup() {
        // No-op
    }
}