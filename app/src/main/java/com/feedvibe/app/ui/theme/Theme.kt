package com.feedvibe.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.feedvibe.app.data.prefs.AppSettings
import com.feedvibe.app.data.prefs.ThemeMode

private fun mix(a: Color, b: Color, t: Float) = lerp(a, b, t)

/** Esquema de color generado a partir del color de acento elegido. */
fun accentScheme(accent: Color, dark: Boolean, amoled: Boolean): ColorScheme {
    val white = Color.White
    val black = Color(0xFF0E1116)
    return if (!dark) {
        lightColorScheme(
            primary = accent,
            onPrimary = white,
            primaryContainer = mix(accent, white, 0.82f),
            onPrimaryContainer = mix(accent, black, 0.6f),
            secondary = mix(accent, Color(0xFF5F6B7A), 0.55f),
            onSecondary = white,
            secondaryContainer = mix(accent, white, 0.88f),
            onSecondaryContainer = mix(accent, black, 0.7f),
            tertiary = Color(0xFFF59E0B),
            tertiaryContainer = Color(0xFFFFE7B3),
            onTertiaryContainer = Color(0xFF3D2800),
            background = mix(accent, white, 0.965f),
            surface = mix(accent, white, 0.965f),
            surfaceVariant = mix(accent, white, 0.9f),
            surfaceContainerLowest = white,
            surfaceContainerLow = mix(accent, white, 0.95f),
            surfaceContainer = mix(accent, white, 0.93f),
            surfaceContainerHigh = mix(accent, white, 0.91f),
            surfaceContainerHighest = mix(accent, white, 0.88f),
            outlineVariant = mix(accent, white, 0.8f),
        )
    } else {
        val base = if (amoled) Color.Black else Color(0xFF10131A)
        val light = mix(accent, white, 0.35f)
        darkColorScheme(
            primary = light,
            onPrimary = mix(accent, black, 0.7f),
            primaryContainer = mix(accent, black, 0.45f),
            onPrimaryContainer = mix(accent, white, 0.85f),
            secondary = mix(light, Color(0xFFB0BAC8), 0.5f),
            secondaryContainer = mix(accent, base, 0.75f),
            onSecondaryContainer = mix(accent, white, 0.85f),
            tertiary = Color(0xFFFFC24D),
            tertiaryContainer = Color(0xFF5A3F00),
            onTertiaryContainer = Color(0xFFFFE7B3),
            background = base,
            surface = base,
            surfaceVariant = mix(accent, base, 0.85f),
            surfaceContainerLowest = base,
            surfaceContainerLow = mix(accent, base, if (amoled) 0.95f else 0.93f),
            surfaceContainer = mix(accent, base, if (amoled) 0.92f else 0.9f),
            surfaceContainerHigh = mix(accent, base, if (amoled) 0.89f else 0.87f),
            surfaceContainerHighest = mix(accent, base, if (amoled) 0.86f else 0.84f),
            outlineVariant = mix(accent, base, 0.7f),
        )
    }
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun isDark(settings: AppSettings): Boolean = when (settings.themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun FeedVibeTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = isDark(settings)
    val context = LocalContext.current
    var scheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> accentScheme(Color(settings.accent.argb), dark, settings.amoled)
    }
    if (dark && settings.amoled) {
        scheme = scheme.copy(background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black)
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(scheme.background.toArgb()))
        }
    }
    MaterialTheme(colorScheme = scheme, shapes = AppShapes, content = content)
}
