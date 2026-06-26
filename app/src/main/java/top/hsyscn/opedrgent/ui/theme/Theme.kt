package top.hsyscn.opedrgent.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Blue80,
    onPrimary = Color.White,
    primaryContainer = Blue40.copy(alpha = 0.2f),
    secondary = BlueGrey80,
    onSecondary = Color.White,
    secondaryContainer = SurfaceLight_Dark,
    tertiary = Teal80,
    background = BgGray_Dark,
    onBackground = TextDark_Dark,
    surface = CardWhite_Dark,
    onSurface = TextDark_Dark,
    surfaceVariant = Color(0xFF2A2D33),
    onSurfaceVariant = TextGrey_Dark,
    outline = BorderLight_Dark,
    outlineVariant = DividerColor_Dark,
    error = DangerRed,
    errorContainer = ErrorBackground,
    // Extended semantic (used via MaterialTheme.colorScheme)
    surfaceContainerLow = SurfaceElevated_Dark,
    surfaceContainer = CardBackground_Dark,
    surfaceContainerHigh = SurfaceLight_Dark,
    inverseSurface = InterviewDarkBg,
    inverseOnSurface = TextDark_Dark,
    inversePrimary = InterviewPurple,
)

private val LightColorScheme = lightColorScheme(
    primary = Blue40,
    onPrimary = Color.White,
    primaryContainer = LightBlueBg,
    secondary = BlueGrey40,
    onSecondary = Color.White,
    secondaryContainer = SurfaceLight,
    tertiary = Teal40,
    background = DarkBackground,
    onBackground = TextDark,
    surface = DarkSurface,
    onSurface = TextDark,
    surfaceVariant = Color(0xFFE8EDF5),
    onSurfaceVariant = TextGrey,
    outline = BorderLight,
    outlineVariant = DividerColor,
    error = DangerRed,
    errorContainer = ErrorBackground,
    // Extended semantic (used via MaterialTheme.colorScheme)
    surfaceContainerLow = SurfaceElevated,
    surfaceContainer = CardBackground,
    surfaceContainerHigh = SurfaceLight,
    inverseSurface = InterviewDarkBg,
    inverseOnSurface = Color.White,
    inversePrimary = InterviewPurple,
)

@Composable
fun OpedrgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val customColors = if (darkTheme) DarkCustomColors else LightCustomColors

    CompositionLocalProvider(LocalCustomColors provides customColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content,
        )
    }
}
