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
    /** Vibrant style: gradient hero/summary cards and multi-colour accents. */
    val vibrant: Boolean = false,
    /** Hero (home index / analysis) gradient; on vibrant it is saturated and content sits on it in white. */
    val heroGradient: List<Color> = listOf(heroStart, heroEnd),
    /** Portfolio summary gradient. */
    val summaryGradient: List<Color> = listOf(heroStart, heroEnd),
    /** Credit-card style gradient for the bills card. */
    val cardGradient: List<Color> = listOf(heroStart, heroEnd),
    /** Palette for quick actions, index cards, avatars. */
    val accents: List<Color> = listOf(chart1, chart4, chart3, neutral, bull, chart2),
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

// Vibrant style: saturated gradients and a six-hue accent palette.
private val VibrantAccents = listOf(Color(0xFF8B5CF6), Color(0xFF0EA5E9), Color(0xFFEC4899), Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFFF97316))
private val VibrantDarkMarket = DarkMarket.copy(
    bull = Color(0xFF2EE6A6), bear = Color(0xFFFF5C8A), neutral = Color(0xFFFFC53D),
    chart1 = Color(0xFFA78BFA), chart2 = Color(0xFFFB923C), chart3 = Color(0xFFF472B6), chart4 = Color(0xFF38BDF8),
    heroStart = Color(0xFF3730A3), heroEnd = Color(0xFFBE185D), glow = Color(0xFF8B5CF6),
    vibrant = true,
    heroGradient = listOf(Color(0xFF3730A3), Color(0xFF6D28D9), Color(0xFFBE185D)),
    summaryGradient = listOf(Color(0xFF0F766E), Color(0xFF1D4ED8), Color(0xFF6D28D9)),
    cardGradient = listOf(Color(0xFFEA580C), Color(0xFFDB2777), Color(0xFF7C3AED)),
    accents = VibrantAccents,
)
private val VibrantLightMarket = LightMarket.copy(
    bull = Color(0xFF00A870), bear = Color(0xFFE11D48), neutral = Color(0xFFD97706),
    chart1 = Color(0xFF7C3AED), chart2 = Color(0xFFEA580C), chart3 = Color(0xFFDB2777), chart4 = Color(0xFF0284C7),
    heroStart = Color(0xFF4F46E5), heroEnd = Color(0xFFDB2777), glow = Color(0xFF8B5CF6),
    vibrant = true,
    heroGradient = listOf(Color(0xFF4F46E5), Color(0xFF7C3AED), Color(0xFFDB2777)),
    summaryGradient = listOf(Color(0xFF0D9488), Color(0xFF2563EB), Color(0xFF7C3AED)),
    cardGradient = listOf(Color(0xFFF97316), Color(0xFFDB2777), Color(0xFF7C3AED)),
    accents = VibrantAccents,
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

private val VibrantDarkScheme = DarkScheme.copy(
    primary = Color(0xFFA78BFA), onPrimary = Color(0xFF1A0B3D),
    primaryContainer = Color(0xFF3B2A6B), onPrimaryContainer = Color(0xFFEDE7FF),
    secondary = Color(0xFFA78BFA), secondaryContainer = Color(0xFF2E2350), onSecondaryContainer = Color(0xFFEDE7FF),
    tertiary = Color(0xFFF472B6),
    background = Color(0xFF0B0A14), surface = Color(0xFF0B0A14),
    surfaceContainerLow = Color(0xFF110F1C), surfaceContainer = Color(0xFF16131F), surfaceContainerHigh = Color(0xFF1E1A2B),
    surfaceContainerHighest = Color(0xFF262136), outline = Color(0xFF2E2840), outlineVariant = Color(0xFF221E30),
    error = Color(0xFFFF5C8A),
)
private val VibrantLightScheme = LightScheme.copy(
    primary = Color(0xFF7C3AED), onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE5FF), onPrimaryContainer = Color(0xFF3B0F8C),
    secondary = Color(0xFF7C3AED), secondaryContainer = Color(0xFFEFE7FF), onSecondaryContainer = Color(0xFF4C1D95),
    tertiary = Color(0xFFDB2777),
    background = Color(0xFFF8F6FF), surface = Color(0xFFF8F6FF),
    surfaceContainerHigh = Color(0xFFF2EEFB), surfaceContainerHighest = Color(0xFFEAE4F7),
    outline = Color(0xFFE6E0F2), outlineVariant = Color(0xFFEFEAF8), error = Color(0xFFE11D48),
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
fun CapitolTheme(darkTheme: Boolean, dynamicColor: Boolean = false, vibrant: Boolean = true, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        darkTheme -> if (vibrant) VibrantDarkScheme else DarkScheme
        else -> if (vibrant) VibrantLightScheme else LightScheme
    }
    val market = when {
        vibrant -> if (darkTheme) VibrantDarkMarket else VibrantLightMarket
        else -> if (darkTheme) DarkMarket else LightMarket
    }
    CompositionLocalProvider(LocalMarketColors provides market) {
        MaterialTheme(colorScheme = scheme, typography = CapitolType, shapes = CapitolShapes) {
            // Root surface so all text defaults to the correct on-background colour in both themes.
            androidx.compose.material3.Surface(color = scheme.background, contentColor = scheme.onBackground, content = content)
        }
    }
}

object CapitolColors {
    val market: MarketColors @Composable get() = LocalMarketColors.current
}

/**
 * Content placed on a saturated gradient (vibrant hero, summary and card tiles): text, labels, pills and
 * chart axes switch to white so they stay readable. On the premium style it changes nothing.
 */
@Composable
fun OnGradient(enabled: Boolean = LocalMarketColors.current.vibrant, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val s = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = s.copy(
            onSurface = Color.White, onSurfaceVariant = Color.White.copy(alpha = 0.78f), onBackground = Color.White,
            surface = Color(0xFF1B1238), outline = Color.White.copy(alpha = 0.28f), outlineVariant = Color.White.copy(alpha = 0.18f),
            surfaceContainerHigh = Color.White.copy(alpha = 0.18f), surfaceVariant = Color.White.copy(alpha = 0.22f),
            primary = Color.White, onPrimary = Color(0xFF2A1260),
        ),
        typography = MaterialTheme.typography, shapes = MaterialTheme.shapes,
    ) {
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides Color.White,
            LocalMarketColors provides LocalMarketColors.current.copy(
                bull = Color(0xFF6EFFC4), bear = Color(0xFFFFB3C7), grid = Color(0x33FFFFFF),
            ),
        ) { content() }
    }
}
