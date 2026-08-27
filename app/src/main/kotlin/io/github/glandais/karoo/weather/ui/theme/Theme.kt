package io.github.glandais.karoo.weather.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import io.github.glandais.karoo.weather.ui.theme.ColorPair

/*
 * NOTE ON FILE LOCATION: PLAN WP5 fixes both the path (`ui/theme/Theme.kt`) and the package
 * (`io.github.glandais.karoo.weather.ui`, so that `AppTheme` sits beside `WeatherApp`). The two
 * disagree; PLAN rule 2 makes the declared signature — package included — the one that wins.
 */

/**
 * Light scheme, DESIGN §5.
 *
 * Deliberately not the `#6200EE / #03DAC5` Android template palette: that is boilerplate from the
 * karoo-ext sample, not an identity. The values are the same `Wx` pairs the data fields use, so the
 * app and the fields beside it on the page cannot drift apart.
 */
private val LightColors =
    lightColorScheme(
        primary = Color(0xFF1B4F7A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD8E7F4),
        onPrimaryContainer = Color(0xFF10314C),
        secondary = Color(0xFF5A5A5A),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFFB07200),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF000000),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFF2F2F2),
        onSurfaceVariant = Color(0xFF5A5A5A),
        outline = Color(0xFFD0D0D0),
        outlineVariant = Color(0xFFE4E4E4),
        error = Color(0xFFA30000),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF7DCDC),
        onErrorContainer = Color(0xFF5C0000),
    )

/** Dark scheme, DESIGN §5. Karoo applies night mode system-wide; a forced-light app is a flash. */
private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF9FD4FF),
        onPrimary = Color(0xFF00243D),
        primaryContainer = Color(0xFF10314C),
        onPrimaryContainer = Color(0xFFD8E7F4),
        secondary = Color(0xFFB0B0B0),
        onSecondary = Color(0xFF000000),
        tertiary = Color(0xFFFFC048),
        onTertiary = Color(0xFF2E1F00),
        background = Color(0xFF000000),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF121212),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF1E1E1E),
        onSurfaceVariant = Color(0xFFB0B0B0),
        outline = Color(0xFF3A3A3A),
        outlineVariant = Color(0xFF2A2A2A),
        error = Color(0xFFFF5454),
        onError = Color(0xFF2A0000),
        errorContainer = Color(0xFF3A0F0F),
        onErrorContainer = Color(0xFFFFD5D5),
    )

/**
 * Every number in the app is monospaced (DESIGN §1.3): digits must not change width between ticks,
 * or a value read at arm's length appears to move when only its last digit changed.
 */
private val AppTypography =
    Typography().let { base ->
        base.copy(
            displayLarge = base.displayLarge.mono(),
            displayMedium = base.displayMedium.mono(),
            displaySmall = base.displaySmall.mono(),
            headlineLarge = base.headlineLarge.mono(),
            headlineMedium = base.headlineMedium.mono(),
        )
    }

private fun TextStyle.mono(): TextStyle = copy(fontFamily = FontFamily.Monospace)

/** Material3 scheme derived from `isSystemInDarkTheme()`, built from the same `Wx` pairs. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}

/** The `Wx` token resolved against the current system theme. */
@Composable @ReadOnlyComposable fun ColorPair.asColor(): Color = Color(pick(isSystemInDarkTheme()))

/** The `Wx` token resolved against an explicit side, for callers that already know it. */
fun ColorPair.toColor(night: Boolean): Color = Color(pick(night))
