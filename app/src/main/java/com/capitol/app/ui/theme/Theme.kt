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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

private val DarkMarket = MarketColors(
    bull = Color(0xFF3DDC97), bear = Color(0xFFFF5C7A), neutral = Color(0xFFFFC857),
    chart1 = Color(0xFF7C9CFF), chart2 = Color(0xFFFFB86B), chart3 = Color(0xFFC792EA), chart4 = Color(0xFF4DD0E1),
    grid = Color(0x22FFFFFF), heroStart = Color(0xFF101A3A), heroEnd = Color(0xFF0A2E2A), glow = Color(0xFF3DDC97),
    isDark = true,
)
private val LightMarket = MarketColors(
    bull = Color(0xFF0E9F6E), bear = Color(0xFFE02454), neutral = Color(0xFFD89B00),
    chart1 = Color(0xFF3355FF), chart2 = Color(0xFFF08A24), chart3 = Color(0xFF8E44AD), chart4 = Color(0xFF0097A7),
    grid = Color(0x1A000000), heroStart = Color(0xFFDCE6FF), heroEnd = Color(0xFFD5F5EA), glow = Color(0xFF0E9F6E),
    isDark = false,
)

val LocalMarketColors = staticCompositionLocalOf { DarkMarket }

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7C9CFF), onPrimary = Color(0xFF0A1030),
    secondary = Color(0xFF3DDC97), onSecondary = Color(0xFF00281A),
    tertiary = Color(0xFFFFC857),
    background = Color(0xFF070B14), onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF0E1422), onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF182033), onSurfaceVariant = Color(0xFFA6B0C3),
    surfaceContainer = Color(0xFF121A2B), surfaceContainerHigh = Color(0xFF182136),
    outline = Color(0xFF2A3550), error = Color(0xFFFF5C7A),
)
private val LightScheme = lightColorScheme(
    primary = Color(0xFF3355FF), onPrimary = Color.White,
    secondary = Color(0xFF0E9F6E), onSecondary = Color.White,
    tertiary = Color(0xFFD89B00),
    background = Color(0xFFF4F7FB), onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE9EEF6), onSurfaceVariant = Color(0xFF55607A),
    surfaceContainer = Color(0xFFF0F3F9), surfaceContainerHigh = Color(0xFFE8EDF5),
    outline = Color(0xFFD0D7E4), error = Color(0xFFE02454),
)

private val CapitolType = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Tabular figures so prices don't jitter while animating. */
val NumberStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)

@Composable
fun CapitolTheme(darkTheme: Boolean, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(LocalMarketColors provides if (darkTheme) DarkMarket else LightMarket) {
        MaterialTheme(colorScheme = scheme, typography = CapitolType) {
            // Root surface so all text defaults to the correct on-background colour in both themes.
            androidx.compose.material3.Surface(color = scheme.background, contentColor = scheme.onBackground, content = content)
        }
    }
}

object CapitolColors {
    val market: MarketColors @Composable get() = LocalMarketColors.current
}
