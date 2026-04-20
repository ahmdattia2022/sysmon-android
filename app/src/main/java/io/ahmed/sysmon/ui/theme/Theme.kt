package io.ahmed.sysmon.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Brand fallback palette — used when Material You / dynamic color is unavailable
// (pre-Android 12) or explicitly disabled. Chosen for LAN-networking tool vibe:
// blue primary, green success, red error, solid WCAG AA contrast on surfaces.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F77B4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4E6F5),
    onPrimaryContainer = Color(0xFF0B2E4A),
    secondary = Color(0xFF2CA02C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFF0DB),
    onSecondaryContainer = Color(0xFF0E3A0E),
    tertiary = Color(0xFFB07A1E),
    tertiaryContainer = Color(0xFFF5E4C3),
    onTertiaryContainer = Color(0xFF3A2706),
    error = Color(0xFFD62728),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBD9D9),
    onErrorContainer = Color(0xFF4A0A0A),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7CB7DD),
    onPrimary = Color(0xFF082A44),
    primaryContainer = Color(0xFF174B70),
    onPrimaryContainer = Color(0xFFD4E6F5),
    secondary = Color(0xFF8CC98A),
    onSecondary = Color(0xFF0E3A0E),
    secondaryContainer = Color(0xFF1E5D1E),
    onSecondaryContainer = Color(0xFFDFF0DB),
    tertiary = Color(0xFFE1B570),
    tertiaryContainer = Color(0xFF5C3F10),
    onTertiaryContainer = Color(0xFFF5E4C3),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF4A0A0A),
    errorContainer = Color(0xFF6E1A1A),
    onErrorContainer = Color(0xFFFBD9D9),
)

// Typography — builds a clear hierarchy. Heading weight heavy, body regular, labels dimmer.
private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun SysmonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You: opt-in when the OS supports it. Falls back to the brand palette
    // on < Android 12 or if the system has no wallpaper-derived scheme.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content
    )
}
