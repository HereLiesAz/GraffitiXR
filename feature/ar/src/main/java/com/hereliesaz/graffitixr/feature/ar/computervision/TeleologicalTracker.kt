
// ~~~ FILE: ./feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/computervision/TeleologicalTracker.kt ~~~
package com.hereliesaz.graffitixr.feature.ar.computervision

import com.hereliesaz.graffitixr.nativebridge.SlamManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TeleologicalTracker @Inject constructor(
    private val slamManager: SlamManager
) {
    fun updateAnchorTransform(matrix: FloatArray) {
        slamManager.updateAnchorTransform(matrix)
    }
}
