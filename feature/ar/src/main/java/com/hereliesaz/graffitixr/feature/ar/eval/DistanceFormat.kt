package com.hereliesaz.graffitixr.feature.ar.eval

/** Formats a camera→point distance for the AR HUD, honoring the imperial/metric preference. */
object DistanceFormat {
    private const val FEET_PER_METER = 3.28084f

    /** @return e.g. "2.3 m" / "7.5 ft"; "—" for invalid (<= 0) distances. */
    fun format(meters: Float, imperial: Boolean): String {
        if (meters <= 0f) return "—"
        return if (imperial) "%.1f ft".format(meters * FEET_PER_METER) else "%.1f m".format(meters)
    }
}
