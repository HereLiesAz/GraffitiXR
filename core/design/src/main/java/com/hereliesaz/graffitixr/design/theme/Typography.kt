package com.hereliesaz.graffitixr.design.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.design.R

// NOTE on non-Latin/accented glyph coverage (audited 2026-08-14):
// blackout_midnight.ttf's cmap only covers ~100 ASCII codepoints, so accented Latin,
// Cyrillic, and CJK text does NOT render in this stylized face. This is intentional and
// safe *as long as* Android's own per-glyph font fallback is doing its job, which it is
// here: this module targets Android only (androidx.compose.ui:ui, minSdk 26), and on
// Android, Compose's Text/BasicText pipeline draws through the platform's native
// android.text/android.graphics stack (StaticLayout/MeasuredText -> minikin), which
// performs automatic per-glyph fallback to the system font chain for any codepoint
// missing from the requested Typeface -- this happens beneath the Typeface object
// itself and does not require any app-side "fallback font list". That is a genuinely
// different behavior from Compose Multiplatform/Desktop (Skiko-based), which lacks
// automatic OS font fallback and *does* require an explicit fallback chain -- this app
// is not that target, so no such chain is needed or safely constructible here.
//
// A Compose FontFamily(font1, font2, ...) list is for selecting weight/style *variants*
// of one face, not a priority fallback chain for missing glyphs (adding a second
// arbitrary face here would not add fallback coverage and could make weight/style
// resolution ambiguous for the primary ASCII text this face is meant for). No
// suppressing config (fontVariationSettings, unusual FontWeight axes, etc.) is present
// below, so nothing here defeats Android's built-in fallback.
//
// Net effect for e.g. French "café": "caf" renders in Blackout Midnight, "é" renders in
// the system fallback sans-serif -- a real, visible style seam mid-word, but NOT a
// missing-glyph/tofu bug. Fixing the seam would mean not using this display face for
// long-form/translated body text at all, which is a product decision out of scope for
// this batch. Flagging as a known follow-up rather than "fixed" here.
val BlackoutFontFamily = FontFamily(
    Font(R.font.blackout_midnight),
)

// Set of Material typography styles to start with.
// Body/title/label sizes are set for comfortable reading on phone screens.
// Display/headline sizes are decorative and stay large.
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    titleLarge = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    labelLarge = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    displayLarge = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 40.sp,
        lineHeight = 45.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 25.sp,
        lineHeight = 31.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    bodySmall = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelMedium = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
