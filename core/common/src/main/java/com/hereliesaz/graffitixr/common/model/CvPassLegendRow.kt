package com.hereliesaz.graffitixr.common.model

/**
 * One pass in the CV preview's legend: its name, its colour on the preview (0xAARRGGBB, opaque), and
 * how many of its targets the wall has answered so far.
 */
data class CvPassLegendRow(val name: String, val argb: Int, val targets: Int, val confirmed: Int)
