package app.kosh.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp
import app.kosh.data.AppSettings
import app.kosh.data.ThemeMode

private val Brand = Color(0xFF0F766E)
private val BrandDark = Color(0xFF5EEAD4)

private val LightScheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCF2EC),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    secondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFB45309),
    tertiaryContainer = Color(0xFFFFDDB8),
    background = Color(0xFFF7FAF9),
    surface = Color(0xFFF7FAF9),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F5F4),
    surfaceContainer = Color(0xFFEBF0EF),
    surfaceContainerHigh = Color(0xFFE5EBEA),
    surfaceContainerHighest = Color(0xFFDFE5E4),
    error = Color(0xFFBA1A1A),
)

private val DarkScheme = darkColorScheme(
    primary = BrandDark,
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFFCCF2EC),
    secondary = Color(0xFFB1CCC6),
    secondaryContainer = Color(0xFF334B47),
    tertiary = Color(0xFFFFB86C),
    tertiaryContainer = Color(0xFF6A3B00),
    background = Color(0xFF0E1514),
    surface = Color(0xFF0E1514),
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF161D1C),
    surfaceContainer = Color(0xFF1A2120),
    surfaceContainerHigh = Color(0xFF242B2A),
    surfaceContainerHighest = Color(0xFF2F3635),
)

private fun ColorScheme.amoled() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF181818),
    surfaceContainerHighest = Color(0xFF202020),
)

/** Colours with meaning. Never used alone: amounts also carry a sign and an icon. */
@Immutable
data class MoneyColors(val income: Color, val expense: Color, val transfer: Color, val warning: Color)

val LocalMoneyColors = staticCompositionLocalOf {
    MoneyColors(Color(0xFF15803D), Color(0xFFC2410C), Color(0xFF2563EB), Color(0xFFB45309))
}

private val LightMoney = MoneyColors(Color(0xFF15803D), Color(0xFFC2410C), Color(0xFF2563EB), Color(0xFFB45309))
private val DarkMoney = MoneyColors(Color(0xFF4ADE80), Color(0xFFFB923C), Color(0xFF60A5FA), Color(0xFFFBBF24))

/** Tabular figures so amounts line up in lists. */
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

private val KoshTypography = Typography().run {
    copy(
        displayLarge = displayLarge.merge(Tabular).copy(fontWeight = FontWeight.SemiBold),
        displayMedium = displayMedium.merge(Tabular).copy(fontWeight = FontWeight.SemiBold),
        displaySmall = displaySmall.merge(Tabular).copy(fontWeight = FontWeight.SemiBold),
        headlineLarge = headlineLarge.merge(Tabular).copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.merge(Tabular).copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.merge(Tabular).copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.merge(Tabular).copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.merge(Tabular),
        bodyMedium = bodyMedium.merge(Tabular),
        labelLarge = labelLarge.merge(Tabular).copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(fontSize = 11.sp),
    )
}

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
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    if (dark && settings.amoled) scheme = scheme.amoled()
    androidx.compose.runtime.CompositionLocalProvider(LocalMoneyColors provides if (dark) DarkMoney else LightMoney) {
        MaterialTheme(colorScheme = scheme, typography = KoshTypography, content = content)
    }
}
