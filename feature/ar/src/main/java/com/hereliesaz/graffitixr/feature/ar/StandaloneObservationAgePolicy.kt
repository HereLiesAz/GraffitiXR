package com.hereliesaz.graffitixr.feature.ar

/**
 * Stale-frame gate for standalone tracking — now owned by SphereSLAM.
 *
 * The age check (trustworthy only for a REALTIME camera timestamp source, never guessed for UNKNOWN)
 * moved into the published library (`:reloc`); these aliases keep GraffitiXR's names while the
 * implementation is the library's. Default `maxRealtimeAgeMs` is unchanged (250 ms).
 *
 * @see com.hereliesaz.sphereslam.reloc.ObservationAgePolicy
 */
typealias StandaloneCameraTimestampSource = com.hereliesaz.sphereslam.reloc.CameraTimestampSource
typealias StandaloneObservationAgeConfig = com.hereliesaz.sphereslam.reloc.ObservationAgeConfig
typealias StandaloneObservationAge = com.hereliesaz.sphereslam.reloc.ObservationAge
typealias StandaloneObservationAgePolicy = com.hereliesaz.sphereslam.reloc.ObservationAgePolicy
