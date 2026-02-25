package com.hereliesaz.graffitixr.design.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.design.R

val BlackoutFontFamily = FontFamily(
    Font(R.font.blackout_midnight)
)

// Set of Material typography styles to start with - Scaled down ~30%
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    titleLarge = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 8.sp,
        lineHeight = 11.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.25.sp
    ),
    labelLarge = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
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
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
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
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.1.sp
    ),
    bodySmall = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 8.sp,
        lineHeight = 11.sp,
        letterSpacing = 0.4.sp
    ),
    labelMedium = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 8.sp,
        lineHeight = 11.sp,
        letterSpacing = 0.5.sp
    )
)
