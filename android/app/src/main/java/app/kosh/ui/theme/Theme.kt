package app.kosh.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kosh.data.AppSettings
import app.kosh.data.ThemeMode
import app.kosh.data.ThemePalette

/**
 * One colour theme: an accent for light and dark mode plus the faint tint its neutrals carry, so
 * backgrounds and cards feel part of the same family. Everything else (text, outlines, money
 * colours) is shared, which keeps contrast checked once for all themes.
 */
private data class Palette(
    val primary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    val secondary: Color, val secondaryContainer: Color, val onSecondaryContainer: Color,
    val darkPrimary: Color, val darkOnPrimary: Color, val darkPrimaryContainer: Color, val darkOnPrimaryContainer: Color,
    val darkSecondary: Color, val darkSecondaryContainer: Color,
    /** Neutral background tint for light mode, and the dark mode base. */
    val lightBg: Color, val lightContainer: Color, val lightHigh: Color, val lightHighest: Color, val lightOutline: Color,
    val darkBg: Color, val darkLow: Color, val darkContainer: Color, val darkHigh: Color, val darkHighest: Color,
)

private val Palettes = mapOf(
    ThemePalette.MINT to Palette(
        Color(0xFF0B7A6E), Color(0xFFC9F0E7), Color(0xFF00201B), Color(0xFF4A635E), Color(0xFFD6ECE6), Color(0xFF07201B),
        Color(0xFF5EDCC6), Color(0xFF00372F), Color(0xFF005046), Color(0xFFB9F2E4), Color(0xFFB2CCC6), Color(0xFF2E4844),
        Color(0xFFF3F7F5), Color(0xFFEDF3F1), Color(0xFFE6EEEB), Color(0xFFDDE7E3), Color(0xFFD3DDDA),
        Color(0xFF0C1312), Color(0xFF151D1C), Color(0xFF1A2321), Color(0xFF232D2B), Color(0xFF2E3836),
    ),
    ThemePalette.OCEAN to Palette(
        Color(0xFF0B63B6), Color(0xFFD3E4FF), Color(0xFF001C38), Color(0xFF535F70), Color(0xFFD7E3F7), Color(0xFF101C2B),
        Color(0xFFA2C9FF), Color(0xFF00315C), Color(0xFF004882), Color(0xFFD3E4FF), Color(0xFFBBC7DB), Color(0xFF3B4858),
        Color(0xFFF4F7FB), Color(0xFFECF1F7), Color(0xFFE5ECF4), Color(0xFFDCE4EE), Color(0xFFD3DBE5),
        Color(0xFF0D1218), Color(0xFF161C23), Color(0xFF1B2129), Color(0xFF242B34), Color(0xFF2F3640),
    ),
    ThemePalette.INDIGO to Palette(
        Color(0xFF4F46E5), Color(0xFFE2DFFF), Color(0xFF140A63), Color(0xFF5C5D72), Color(0xFFE1E0F9), Color(0xFF191A2C),
        Color(0xFFC3C0FF), Color(0xFF241A8F), Color(0xFF3B32C6), Color(0xFFE2DFFF), Color(0xFFC5C4DD), Color(0xFF444559),
        Color(0xFFF6F6FC), Color(0xFFEFEFF8), Color(0xFFE9E9F4), Color(0xFFE1E1EE), Color(0xFFD7D7E6),
        Color(0xFF111118), Color(0xFF1A1A22), Color(0xFF1F1F28), Color(0xFF292933), Color(0xFF34343E),
    ),
    ThemePalette.GRAPHITE to Palette(
        Color(0xFF334155), Color(0xFFDDE3EC), Color(0xFF0F172A), Color(0xFF526070), Color(0xFFE2E8F0), Color(0xFF111827),
        Color(0xFFB7C4D6), Color(0xFF1E293B), Color(0xFF334155), Color(0xFFE2E8F0), Color(0xFFB9C3D0), Color(0xFF3B4655),
        Color(0xFFF5F6F8), Color(0xFFEEF0F3), Color(0xFFE7EAEE), Color(0xFFDFE3E8), Color(0xFFD5DAE0),
        Color(0xFF0F1114), Color(0xFF181B1F), Color(0xFF1D2025), Color(0xFF262A30), Color(0xFF31353C),
    ),
    ThemePalette.PLUM to Palette(
        Color(0xFF8E3A8A), Color(0xFFFFD6F7), Color(0xFF390038), Color(0xFF6D5869), Color(0xFFF7DAEE), Color(0xFF261624),
        Color(0xFFFFA9F1), Color(0xFF5A005A), Color(0xFF732271), Color(0xFFFFD6F7), Color(0xFFDABFD3), Color(0xFF544151),
        Color(0xFFFAF5F8), Color(0xFFF4EDF2), Color(0xFFEEE6EC), Color(0xFFE7DEE5), Color(0xFFDDD3DA),
        Color(0xFF151014), Color(0xFF1F191E), Color(0xFF241D23), Color(0xFF2E272C), Color(0xFF393137),
    ),
    ThemePalette.SUNSET to Palette(
        Color(0xFFB4410F), Color(0xFFFFDBCC), Color(0xFF3A0B00), Color(0xFF77574A), Color(0xFFFFDBCF), Color(0xFF2C160D),
        Color(0xFFFFB596), Color(0xFF5E1700), Color(0xFF842E00), Color(0xFFFFDBCC), Color(0xFFE7BEAE), Color(0xFF5D4034),
        Color(0xFFFBF6F3), Color(0xFFF6EEEA), Color(0xFFF0E7E2), Color(0xFFE9DFD9), Color(0xFFE0D4CD),
        Color(0xFF16110F), Color(0xFF201A17), Color(0xFF261F1C), Color(0xFF302825), Color(0xFF3B332F),
    ),
)

private fun lightScheme(p: Palette) = lightColorScheme(
    primary = p.primary, onPrimary = Color.White, primaryContainer = p.primaryContainer, onPrimaryContainer = p.onPrimaryContainer,
    inversePrimary = p.darkPrimary, // snackbar actions such as Undo
    secondary = p.secondary, onSecondary = Color.White, secondaryContainer = p.secondaryContainer, onSecondaryContainer = p.onSecondaryContainer,
    tertiary = Color(0xFFB45309), onTertiary = Color.White, tertiaryContainer = Color(0xFFFFE2C2), onTertiaryContainer = Color(0xFF2E1500),
    background = p.lightBg, onBackground = Color(0xFF161B1A), surface = p.lightBg, onSurface = Color(0xFF161B1A),
    onSurfaceVariant = Color(0xFF5A6462), surfaceVariant = p.lightHighest,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = p.lightContainer,
    surfaceContainerHigh = p.lightHigh, surfaceContainerHighest = p.lightHighest,
    outline = Color(0xFF7A8583), outlineVariant = p.lightOutline,
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6),
)

private fun darkScheme(p: Palette) = darkColorScheme(
    primary = p.darkPrimary, onPrimary = p.darkOnPrimary, primaryContainer = p.darkPrimaryContainer, onPrimaryContainer = p.darkOnPrimaryContainer,
    inversePrimary = p.primary,
    secondary = p.darkSecondary, secondaryContainer = p.darkSecondaryContainer, onSecondaryContainer = Color(0xFFE2E6E5),
    tertiary = Color(0xFFFFB870), onTertiary = Color(0xFF4A2800), tertiaryContainer = Color(0xFF6A3B00),
    background = p.darkBg, onBackground = Color(0xFFDDE3E1), surface = p.darkBg, onSurface = Color(0xFFDDE3E1),
    onSurfaceVariant = Color(0xFFA9B4B1), surfaceVariant = p.darkHighest,
    surfaceContainerLowest = p.darkBg, surfaceContainerLow = p.darkLow, surfaceContainer = p.darkContainer,
    surfaceContainerHigh = p.darkHigh, surfaceContainerHighest = p.darkHighest,
    outline = Color(0xFF889391), outlineVariant = p.darkHigh,
)

/** The accent of each theme, for the theme picker's swatches. */
fun paletteSwatch(p: ThemePalette): Color = Palettes.getValue(p).primary

private fun ColorScheme.amoled() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0D0F0F),
    surfaceContainer = Color(0xFF121515),
    surfaceContainerHigh = Color(0xFF1A1D1D),
    surfaceContainerHighest = Color(0xFF232727),
    outlineVariant = Color(0xFF222727),
)

/** Colours with meaning. Never used alone: amounts also carry a sign and an icon. */
@Immutable
data class MoneyColors(val income: Color, val expense: Color, val transfer: Color, val warning: Color)

val LocalMoneyColors = staticCompositionLocalOf {
    MoneyColors(Color(0xFF15803D), Color(0xFFD9480F), Color(0xFF2563EB), Color(0xFFD97706))
}

/** True when the app is drawing its dark theme (cards then lean on tone instead of a hairline border). */
val LocalDarkTheme = staticCompositionLocalOf { false }

private val LightMoney = MoneyColors(Color(0xFF15803D), Color(0xFFD9480F), Color(0xFF2563EB), Color(0xFFD97706))
private val DarkMoney = MoneyColors(Color(0xFF4ADE80), Color(0xFFFF8A5B), Color(0xFF60A5FA), Color(0xFFFBBF24))

/**
 * Inter (SIL Open Font License, see assets/licenses/Inter-OFL.txt), bundled as one variable font
 * trimmed to Latin, currency and maths signs. Letters it lacks, such as Devanagari payee names,
 * fall back to the system font.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun inter(weight: Int) = androidx.compose.ui.text.font.Font(
    app.kosh.R.font.inter, FontWeight(weight),
    variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(weight)),
)

val Inter = androidx.compose.ui.text.font.FontFamily(inter(300), inter(400), inter(500), inter(600), inter(700), inter(800))

/** Tabular figures so amounts line up in lists. */
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

private val KoshTypography = Typography().run {
    // Every style uses Inter; the Material defaults keep their sizes and line heights.
    val base = TextStyle(fontFamily = Inter)
    Typography(
        displayLarge = displayLarge.merge(base), displayMedium = displayMedium.merge(base), displaySmall = displaySmall.merge(base),
        headlineLarge = headlineLarge.merge(base), headlineMedium = headlineMedium.merge(base), headlineSmall = headlineSmall.merge(base),
        titleLarge = titleLarge.merge(base), titleMedium = titleMedium.merge(base), titleSmall = titleSmall.merge(base),
        bodyLarge = bodyLarge.merge(base), bodyMedium = bodyMedium.merge(base), bodySmall = bodySmall.merge(base),
        labelLarge = labelLarge.merge(base), labelMedium = labelMedium.merge(base), labelSmall = labelSmall.merge(base),
    )
}.run {
    copy(
        displayLarge = displayLarge.merge(Tabular).copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        displayMedium = displayMedium.merge(Tabular).copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
        displaySmall = displaySmall.merge(Tabular).copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        headlineLarge = headlineLarge.merge(Tabular).copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.merge(Tabular).copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineSmall = headlineSmall.merge(Tabular).copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleMedium = titleMedium.merge(Tabular).copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.merge(Tabular).copy(letterSpacing = 0.1.sp),
        bodyMedium = bodyMedium.merge(Tabular),
        labelLarge = labelLarge.merge(Tabular).copy(fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    )
}

private val KoshShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun KoshTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    var scheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context).copy(surfaceContainerLow = Color.White, surfaceContainerLowest = Color.White)
        dark -> darkScheme(Palettes.getValue(settings.palette))
        else -> lightScheme(Palettes.getValue(settings.palette))
    }
    if (dark && settings.amoled) scheme = scheme.amoled()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalMoneyColors provides if (dark) DarkMoney else LightMoney,
        LocalDarkTheme provides dark,
    ) {
        MaterialTheme(colorScheme = scheme, typography = KoshTypography, shapes = KoshShapes, content = content)
    }
}
