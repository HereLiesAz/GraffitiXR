package com.hereliesaz.graffitixr.common.model

/**
 * The camera frame-rate choices offered in Settings.
 *
 * Stored as a plain `Int` rather than an enum because the value is **persisted**, and 30/60 were
 * already on disk before the device-resolved options existed. Keeping those two as their literal
 * frame rates means every previously-saved preference keeps meaning what it meant, with no
 * migration; the two new options take sentinel values that cannot collide with a real frame rate.
 *
 * The device-resolved pair is the point of this: ARCore only exposes `TARGET_FPS_30` and
 * `TARGET_FPS_60` as filters, so 30 and 60 are the only rates that can be *requested* by name. What
 * a given phone actually supports is a set of `CameraConfig`s with their own `fpsRange`s, and the
 * best of those may be neither 30 nor 60 — so [DEVICE_MAX] picks by inspecting the ranges, and
 * [DEVICE_DEFAULT] declines to filter at all.
 */
object CameraTargetFps {

    /** Fixed 30 fps — the historical default and the battery-pressure fallback. */
    const val FPS_30 = 30

    /** Fixed 60 fps, when the device offers a 60 config for the default camera. */
    const val FPS_60 = 60

    /**
     * Whatever `CameraConfig` ARCore selects for itself — no filtering, no swap.
     *
     * Distinct from asking for 30 and happening to get it: ARCore's own choice is the
     * broadest-compatible config it offers, and on a device where the 30 fps filter matches nothing
     * for the default camera, this is the option that reliably works.
     */
    const val DEVICE_DEFAULT = 0

    /**
     * The highest frame rate the device offers for the default back camera.
     *
     * Chosen by the *upper bound* of each config's `fpsRange`, so a config advertising 15–60
     * outranks a fixed 30 for a user who asked for maximum. Still capped to [FPS_30] under battery
     * pressure when adaptive rate is on — it is the option most likely to cook the phone.
     */
    const val DEVICE_MAX = -1

    /** Settings cycles through these in order. */
    val CYCLE = intArrayOf(FPS_30, FPS_60, DEVICE_DEFAULT, DEVICE_MAX)

    /** The next choice after [current], wrapping. Unknown values fall back to the start of [CYCLE]. */
    fun next(current: Int): Int {
        val i = CYCLE.indexOf(current)
        return if (i < 0) CYCLE[0] else CYCLE[(i + 1) % CYCLE.size]
    }

    /** Display text. Sentinels get names; real rates show the number. */
    fun label(value: Int): String = when (value) {
        DEVICE_DEFAULT -> "Device default"
        DEVICE_MAX -> "Device max"
        else -> value.toString()
    }
}
