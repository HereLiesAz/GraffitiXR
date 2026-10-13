package com.hereliesaz.graffitixr.common.model

/**
 * Settings → Anticipation → Fingerprint: what a new target's MobileGS fingerprint is made of.
 *
 * [superPoint]: SuperPoint features (when its model is loaded) rather than ORB. [closeUps]: the
 * capture photo's half-sphere close-ups (zooms 6×, 3×, 2× over the aim grid) beside its own
 * features. SuperPoint descriptors are 1 KB each against ORB's 32 bytes, so [BOTH] is the strongest
 * and largest, [HALF_SPHERE] the smallest. Applies to the next capture; a fingerprint keeps what it
 * was built with, and relocalization matches whatever it holds.
 */
enum class FingerprintFeatures(val storageKey: String, val superPoint: Boolean, val closeUps: Boolean) {
    /** SuperPoint features of the capture only. */
    SUPERPOINT("superpoint", superPoint = true, closeUps = false),

    /** ORB features of the capture and of its close-ups. */
    HALF_SPHERE("half_sphere", superPoint = false, closeUps = true),

    /** SuperPoint features of the capture and of its close-ups. */
    BOTH("both", superPoint = true, closeUps = true),
    ;

    companion object {
        val DEFAULT: FingerprintFeatures = BOTH

        /** The choice persisted as [key]; unknown or absent keys fall back to [DEFAULT]. */
        fun fromStorageKey(key: String?): FingerprintFeatures =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT

        /** The next choice after [current] for the tap-to-cycle Settings row, wrapping. */
        fun next(current: FingerprintFeatures): FingerprintFeatures =
            entries[(current.ordinal + 1) % entries.size]
    }
}
