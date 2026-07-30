package com.focusguard.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Mint = Color(0xFF63CDBD)
val MintDeep = Color(0xFF174E46)
val MintSoft = Color(0xFF9DE0D4)
val DeepForest = Color(0xFF071D19)
val ForestSurface = Color(0xFF0D2924)
val ForestRaised = Color(0xFF12342E)
val ForestBorder = Color(0xFF28564E)
val WarmIvory = Color(0xFFF0F3EE)
val MutedSage = Color(0xFFA2B5AF)
val Coral = Color(0xFFE37B6E)
val WarmSand = Color(0xFFD8B36A)

// Compatibility aliases used by the service UI and overlays.
val Purple = Mint
val PurpleDark = MintDeep
val PurpleLight = MintSoft
val Green = Mint
val Red = Coral
val Orange = WarmSand
val Yellow = Color(0xFFE8D99A)
val DarkBg = DeepForest
val CardBg = ForestSurface
val TextPrimary = WarmIvory
val TextSecondary = MutedSage
val SurfaceDark = ForestRaised

private val DarkColorScheme = darkColorScheme(
    primary = Mint,
    secondary = MintSoft,
    tertiary = WarmSand,
    background = DeepForest,
    surface = ForestSurface,
    surfaceVariant = ForestRaised,
    outline = ForestBorder,
    onPrimary = DeepForest,
    onSecondary = DeepForest,
    onTertiary = DeepForest,
    onBackground = WarmIvory,
    onSurface = WarmIvory,
    onSurfaceVariant = MutedSage,
    error = Coral,
)

private val FocusGuardTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 42.sp,
        lineHeight = 45.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.5.sp,
    ),
)

private val FocusGuardShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun FocusGuardTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = FocusGuardTypography,
        shapes = FocusGuardShapes,
        content = content
    )
}
