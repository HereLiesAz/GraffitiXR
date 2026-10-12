package com.hereliesaz.graffitixr.common.model

/**
 * Settings → Anticipation: how finely the app anticipates the target's appearance for relocalization,
 * and how far the device's state may throttle it.
 *
 * The anticipation resolution is a half-sphere over the target, the target at its core and the
 * camera's capture view at its pole: [latitude] meridians run from the pole to the equator, cut by
 * [longitude] circles; a preview is taken at the pole and at every cut, aimed at the core (none on the
 * equator, edge-on). [distance] nests that many half-spheres between the target and the capture
 * distance (zooming in), and [roll] takes every view turned that many times, evenly from upright.
 * Up close the frame holds only part of the target, so a nearer half-sphere is repeated over a grid
 * of aim points across the target ([aimsPerSide]), its core moved to each: every part of the wall
 * is anticipated close up, where the artist works.
 * That is the maximum; [batteryWeight], [heatWeight] and [computeWeight] say how far a draining
 * battery, a hot device and a busy tracker may cut it (0 ignores that pressure, 1 may cut it all).
 */
data class AnticipationSettings(
    val distance: Int = DEFAULT_DISTANCE,
    val latitude: Int = DEFAULT_LATITUDE,
    val longitude: Int = DEFAULT_LONGITUDE,
    val roll: Int = DEFAULT_ROLL,
    val batteryWeight: Float = DEFAULT_BATTERY_WEIGHT,
    val heatWeight: Float = DEFAULT_HEAT_WEIGHT,
    val computeWeight: Float = DEFAULT_COMPUTE_WEIGHT,
) {
    /** Every field clamped to its range; non-finite weights fall back to their defaults. */
    fun sanitized(): AnticipationSettings = AnticipationSettings(
        distance = distance.coerceIn(0, MAX_DISTANCE),
        latitude = latitude.coerceIn(0, MAX_LATITUDE),
        longitude = longitude.coerceIn(0, MAX_LONGITUDE),
        roll = roll.coerceIn(1, MAX_ROLL),
        batteryWeight = weight(batteryWeight, DEFAULT_BATTERY_WEIGHT),
        heatWeight = weight(heatWeight, DEFAULT_HEAT_WEIGHT),
        computeWeight = weight(computeWeight, DEFAULT_COMPUTE_WEIGHT),
    )

    /**
     * Aim points per side of half-sphere [shell] (0 nearest; `distance − 1` the capture distance): the
     * frame there spans `(shell + 1) / distance` of what it spanned at capture, so
     * `⌈distance / (shell + 1)⌉` aims per side tile the target. 1 at the capture distance.
     */
    fun aimsPerSide(shell: Int): Int = (distance + shell) / (shell + 1)

    /**
     * Previews per page at the maximum: for each half-sphere, its aims (`aimsPerSide²`) × (the pole
     * and every cut, `1 + latitude × longitude`) × [roll].
     */
    val maxViews: Int get() = sanitized().let { s ->
        (0 until s.distance).sumOf { s.aimsPerSide(it).let { k -> k * k } } * (1 + s.latitude * s.longitude) * s.roll
    }

    companion object {
        // KPM matches up to about 30° of viewing angle: circles at 30° and 60°, meridians 60° apart.
        const val DEFAULT_DISTANCE = 3
        const val DEFAULT_LATITUDE = 6
        const val DEFAULT_LONGITUDE = 2
        // The sensors turn the frame upright before a search: one roll serves.
        const val DEFAULT_ROLL = 1
        const val DEFAULT_BATTERY_WEIGHT = 0.5f
        const val DEFAULT_HEAT_WEIGHT = 1f
        const val DEFAULT_COMPUTE_WEIGHT = 0.5f

        const val MAX_DISTANCE = 6
        const val MAX_LATITUDE = 24
        const val MAX_LONGITUDE = 6
        const val MAX_ROLL = 12

        val DEFAULT = AnticipationSettings()

        private fun weight(value: Float, fallback: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else fallback
    }
}
