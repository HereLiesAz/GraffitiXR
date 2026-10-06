package com.hereliesaz.graffitixr.feature.ar

/**
 * Acceptance gate for a standalone KPM pose — now owned by SphereSLAM.
 *
 * The inlier floor / reprojection ceiling / frame-to-frame continuity logic (with its looser
 * reacquire envelope) moved into the published library (`:reloc`); these aliases keep GraffitiXR's
 * names while the implementation is the library's. Defaults still mirror the pinned artoolkitX KPM
 * binary (reject < 4 matches, reject ICP error > 10).
 *
 * @see com.hereliesaz.sphereslam.reloc.PoseAcceptancePolicy
 */
typealias StandalonePoseAcceptanceConfig = com.hereliesaz.sphereslam.reloc.PoseAcceptanceConfig
typealias StandalonePoseRejection = com.hereliesaz.sphereslam.reloc.PoseRejection
typealias StandalonePoseAcceptance = com.hereliesaz.sphereslam.reloc.PoseAcceptance
typealias StandalonePoseAcceptancePolicy = com.hereliesaz.sphereslam.reloc.PoseAcceptancePolicy
