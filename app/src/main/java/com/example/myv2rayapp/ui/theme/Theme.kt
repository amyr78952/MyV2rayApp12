package com.example.myv2rayapp.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/* ================= Light Colors ================= */

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0),       // Blue
    onPrimary = Color.White,

    secondary = Color(0xFF00ACC1),     // Cyan
    onSecondary = Color.White,

    tertiary = Color(0xFF2E7D32),      // Green (Connected)
    onTertiary = Color.White,

    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF1C1C1C),

    surface = Color.White,
    onSurface = Color(0xFF1C1C1C),

    error = Color(0xFFD32F2F),
    onError = Color.White
)

/* ================= Dark Colors ================= */

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF42A5F5),       // Neon Blue
    onPrimary = Color.Black,

    secondary = Color(0xFF26C6DA),     // Cyan
    onSecondary = Color.Black,

    tertiary = Color(0xFF66BB6A),      // Green (Connected)
    onTertiary = Color.Black,

    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),

    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),

    error = Color(0xFFEF5350),
    onError = Color.Black
)

/* ================= App Theme ================= */

@Composable
fun MyV2rayAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
