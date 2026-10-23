package com.hereliesaz.graffitixr.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val HotPink = Color(0xFFFF00C8)
val Cyan = Color(0xFF00FFFF)
val NeonGreen = Color(0xFF39FF14)

val Black = Color(0xFF000000)

val White = Color(0xFFFFFFFF)

val DarkGrey = Color(0xFF2C2C2C)

val Gray = Color(0xFF5D5D5D)

// Semantic tokens: every HUD, status and danger colour comes from here, never a literal in a screen.

/** Behind HUD pills, cards and banners over the camera: black at a fixed, readable alpha. */
val HudScrim = Color(0xCC000000)

/** Dialog and sheet containers: near-black, a step up from the background. */
val DialogSurface = Color(0xFF141414)

/** All is well (tracking, progress done): the brand lime. */
val StatusOk = NeonGreen

/** Working / attention (searching, partial): the brand cyan. */
val StatusWarn = Cyan

/** Destructive or failed: the brand magenta, never a stock Material red. */
val Danger = HotPink

/** A control that can't be used right now: neutral, so it never reads as tappable over any feed. */
val DisabledContainer = Color(0xFF3A3A3A)
val DisabledContent = Color(0x99FFFFFF)

/** The one shape of every HUD pill, card and banner over the camera. */
val HudPillShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
