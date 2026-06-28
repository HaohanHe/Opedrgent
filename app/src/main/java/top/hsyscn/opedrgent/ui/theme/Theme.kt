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
    primary = PrimaryBrand,
    onPrimary = Color.White,
    primaryContainer = PrimaryRing,
    onPrimaryContainer = Color.White,
    secondary = ForegroundSecondaryDark,
    onSecondary = Color.White,
    secondaryContainer = BackgroundSecondaryDark,
    onSecondaryContainer = ForegroundDark,
    tertiary = PrimaryRing,
    onTertiary = Color.White,
    tertiaryContainer = BackgroundMutedDark,
    onTertiaryContainer = ForegroundDark,
    background = BackgroundDark,
    onBackground = ForegroundDark,
    surface = BackgroundCardDark,
    onSurface = ForegroundDark,
    surfaceVariant = BackgroundMutedDark,
    onSurfaceVariant = ForegroundMutedDark,
    outline = BorderDark,
    outlineVariant = BorderDark,
    error = Error,
    onError = Color.White,
    errorContainer = ErrorDarkBg,
    onErrorContainer = Error,
    surfaceContainerLow = BackgroundMutedDark,
    surfaceContainer = BackgroundSecondaryDark,
    surfaceContainerHigh = BackgroundCardDark,
    inverseSurface = BackgroundLight,
    inverseOnSurface = ForegroundLight,
    inversePrimary = PrimaryRing,
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBrand,
    onPrimary = Color.White,
    primaryContainer = PrimarySubtle,
    onPrimaryContainer = ForegroundAccentLight,
    secondary = ForegroundSecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = BackgroundSecondaryLight,
    onSecondaryContainer = ForegroundLight,
    tertiary = PrimaryRing,
    onTertiary = Color.White,
    tertiaryContainer = BackgroundMutedLight,
    onTertiaryContainer = ForegroundLight,
    background = BackgroundLight,
    onBackground = ForegroundLight,
    surface = BackgroundCardLight,
    onSurface = ForegroundLight,
    surfaceVariant = BackgroundMutedLight,
    onSurfaceVariant = ForegroundMutedLight,
    outline = BorderLight,
    outlineVariant = BorderLightVariant,
    error = Error,
    onError = Color.White,
    errorContainer = ErrorBg,
    onErrorContainer = Error,
    surfaceContainerLow = BackgroundMutedLight,
    surfaceContainer = BackgroundSecondaryLight,
    surfaceContainerHigh = BackgroundCardLight,
    inverseSurface = BackgroundDark,
    inverseOnSurface = ForegroundDark,
    inversePrimary = PrimaryRing,
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
