package com.hereliesaz.graffitixr.feature.ar

/**
 * Tracking-state hysteresis for the standalone backend — now owned by SphereSLAM.
 *
 * This file was GraffitiXR's local copy of the lock/reacquire/bridge/lost state machine. The generic
 * logic moved into the published library (`:reloc`) so every SphereSLAM consumer shares it; these
 * aliases keep GraffitiXR's domain vocabulary (`StandaloneTrackingState`, …) while the implementation
 * is the library's. The proprietary fingerprint reloc still layers on top, unchanged.
 *
 * @see com.hereliesaz.sphereslam.reloc.TrackingStateMachine
 */
typealias StandaloneTrackingState = com.hereliesaz.sphereslam.reloc.TrackingState
typealias StandaloneTrackingStateConfig = com.hereliesaz.sphereslam.reloc.TrackingStateConfig
typealias StandaloneTrackingStateMachine = com.hereliesaz.sphereslam.reloc.TrackingStateMachine
