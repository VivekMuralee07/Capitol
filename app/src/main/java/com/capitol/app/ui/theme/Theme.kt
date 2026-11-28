package com.capitol.app.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Market-specific colours that Material's scheme doesn't cover. */
@Immutable
data class MarketColors(
    val bull: Color,
    val bear: Color,
    val neutral: Color,
    val chart1: Color,
    val chart2: Color,
    val chart3: Color,
    val chart4: Color,
    val grid: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val glow: Color,
    val isDark: Boolean,
)

// Premium fintech palette: near-black / soft-white canvas, quiet surfaces, one indigo accent,
// and softer gain/loss colours so numbers carry the colour, not the chrome.
private val Accent = Color(0xFF8B93FF)
private val AccentLight = Color(0xFF4B55E0)

private val DarkMarket = MarketColors(
    bull = Color(0xFF34D399), bear = Color(0xFFF87171), neutral = Color(0xFFFBBF24),
    chart1 = Accent, chart2 = Color(0xFFF4A261), chart3 = Color(0xFFB794F6), chart4 = Color(0xFF38BDF8),
    grid = Color(0x14FFFFFF), heroStart = Color(0xFF171B2E), heroEnd = Color(0xFF111319), glow = Accent,
    isDark = true,
)
private val LightMarket = MarketColors(
    bull = Color(0xFF059669), bear = Color(0xFFDC2626), neutral = Color(0xFFD97706),
    chart1 = AccentLight, chart2 = Color(0xFFE07A1F), chart3 = Color(0xFF7C3AED), chart4 = Color(0xFF0284C7),
    grid = Color(0x12000000), heroStart = Color(0xFFEEF0FF), heroEnd = Color(0xFFFFFFFF), glow = AccentLight,
    isDark = false,
)

val LocalMarketColors = staticCompositionLocalOf { DarkMarket }

private val DarkScheme = darkColorScheme(
    primary = Accent, onPrimary = Color(0xFF0B0D1F),
    primaryContainer = Color(0xFF23264A), onPrimaryContainer = Color(0xFFDDE0FF),
    // Chips, nav indicator and segmented buttons use the secondary container: a quiet accent tint.
    secondary = Accent, onSecondary = Color(0xFF0B0D1F),
    secondaryContainer = Color(0xFF22253F), onSecondaryContainer = Color(0xFFE3E5FF),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF0A0B0F), onBackground = Color(0xFFF2F4F8),
    surface = Color(0xFF0A0B0F), onSurface = Color(0xFFF2F4F8),
    surfaceVariant = Color(0xFF1A1D25), onSurfaceVariant = Color(0xFF8D94A5),
    surfaceContainerLowest = Color(0xFF0A0B0F), surfaceContainerLow = Color(0xFF101217),
    surfaceContainer = Color(0xFF14161C), surfaceContainerHigh = Color(0xFF1B1E26), surfaceContainerHighest = Color(0xFF22252E),
    outline = Color(0xFF2A2E39), outlineVariant = Color(0xFF1F222B), error = Color(0xFFF87171),
)
private val LightScheme = lightColorScheme(
    primary = AccentLight, onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E8FF), onPrimaryContainer = Color(0xFF151A6B),
    secondary = AccentLight, onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EAFF), onSecondaryContainer = Color(0xFF1C2280),
    tertiary = Color(0xFFD97706),
    background = Color(0xFFF6F7F9), onBackground = Color(0xFF0B0D12),
    surface = Color(0xFFF6F7F9), onSurface = Color(0xFF0B0D12),
    surfaceVariant = Color(0xFFEEF0F4), onSurfaceVariant = Color(0xFF5F6675),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBFBFC),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF1F3F6), surfaceContainerHighest = Color(0xFFE9ECF1),
    outline = Color(0xFFE3E6EC), outlineVariant = Color(0xFFEDEFF3), error = Color(0xFFDC2626),
)

private val CapitolType = Typography().run {
    copy(
        displayMedium = displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
        displaySmall = displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}

private val CapitolShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

/** Tabular (fixed-width) figures in the app's sans font, so prices line up and don't jitter while animating. */
val NumberStyle = TextStyle(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum", letterSpacing = (-0.2).sp)

@Composable
fun CapitolTheme(darkTheme: Boolean, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(LocalMarketColors provides if (darkTheme) DarkMarket else LightMarket) {
        MaterialTheme(colorScheme = scheme, typography = CapitolType, shapes = CapitolShapes) {
            // Root surface so all text defaults to the correct on-background colour in both themes.
            androidx.compose.material3.Surface(color = scheme.background, contentColor = scheme.onBackground, content = content)
        }
    }
}

object CapitolColors {
    val market: MarketColors @Composable get() = LocalMarketColors.current
}
