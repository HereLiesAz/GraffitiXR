package com.hereliesaz.graffitixr.feature.ar.rendering

/** Where to place the artwork when no surface is hit: [WALL_FALLBACK_M] ahead for a wall. */
internal const val WALL_FALLBACK_M = 2.0f

/** Fallback distance when the camera looks down at a table, canvas or paper. */
internal const val TABLE_FALLBACK_M = 0.35f

/** The camera's forward vector points this far down (world y) or more: it is looking at a table. */
internal const val LOOKING_DOWN_FORWARD_Y = -0.7f

/**
 * How far ahead of the camera to place the artwork when no surface was hit. A camera looking down
 * steeply (more than ~45°) is over a table, canvas or paper at arm's length, not facing a wall two
 * metres off; placing it at [WALL_FALLBACK_M] there would put the artwork through the table.
 *
 * @param forwardY the world-space y of the camera's forward (−z) direction, −1 straight down.
 */
internal fun fallbackPlacementDistance(forwardY: Float): Float =
    if (forwardY <= LOOKING_DOWN_FORWARD_Y) TABLE_FALLBACK_M else WALL_FALLBACK_M
