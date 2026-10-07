@file:OptIn(com.hereliesaz.sphereslam.reloc.ExperimentalSphereSlamRelocApi::class)

package com.hereliesaz.graffitixr.feature.ar

/**
 * Temporal low-pass for the standalone render pose — now owned by SphereSLAM.
 *
 * The translation-lerp + quaternion-nlerp smoother (snapping through large, divergent moves so the
 * overlay never lags real motion) moved into the published library (`:reloc`); this alias keeps
 * GraffitiXR's name while the implementation is the library's. Snap thresholds are identical
 * (0.20 m / 15°), so behaviour is unchanged. It remains a stabilizer, not a drift corrector — the
 * proprietary reloc-corroboration fusion in [SphereSlamStandaloneTrackingAnalyzer] does that; this
 * only smooths what that produces.
 *
 * @see com.hereliesaz.sphereslam.reloc.PoseStabilizer
 */
typealias StandalonePoseStabilizer = com.hereliesaz.sphereslam.reloc.PoseStabilizer
