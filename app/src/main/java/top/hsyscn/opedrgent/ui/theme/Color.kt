package top.hsyscn.opedrgent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ========== Doubao design tokens ==========

// Primary brand
val PrimaryBrand = Color(0xFF0065FD)
val PrimaryHover = Color(0xFF0057DA)
val PrimarySubtle = Color(0xFFE5E9FF)
val PrimaryRing = Color(0xFF557FFF)

// Light mode surfaces & text
val BackgroundLight = Color(0xFFFFFFFF)
val BackgroundSecondaryLight = Color(0xFFF9F9FA)
val BackgroundMutedLight = Color(0xFFEFF1F4)
val BackgroundCardLight = Color(0xFFFFFFFF)
val ForegroundLight = Color(0xFF0E1115)
val ForegroundSecondaryLight = Color(0xFF333942)
val ForegroundMutedLight = Color(0xFF7F8D9F)
val ForegroundAccentLight = Color(0xFF00266B)

val BorderLight = Color(0xFFE7EAEF)
val BorderLightVariant = Color(0xFFF0F2F5)

// Semantic status colors
val Success = Color(0xFF22C55E)
val SuccessBg = Color(0xFFECFDF5)
val Error = Color(0xFFEF4444)
val ErrorBg = Color(0xFFFEF2F2)
val Warning = Color(0xFFF59E0B)
val WarningBg = Color(0xFFFFFBEB)

// Dark mode surfaces & text
val BackgroundDark = Color(0xFF0E1115)
val BackgroundSecondaryDark = Color(0xFF161A1F)
val BackgroundCardDark = Color(0xFF1A1E23)
val BackgroundMutedDark = Color(0xFF22272D)
val ForegroundDark = Color(0xFFEFF1F4)
val ForegroundSecondaryDark = Color(0xFFC0C5CE)
val ForegroundMutedDark = Color(0xFF7F8D9F)
val ForegroundAccentDark = Color(0xFFC0D8FF)

val BorderDark = Color(0xFF2A2F36)
val InputDark = Color(0xFF2A2F36)

val SuccessDarkBg = Color(red = 34f / 255, green = 197f / 255, blue = 94f / 255, alpha = 0.12f)
val ErrorDarkBg = Color(red = 239f / 255, green = 68f / 255, blue = 68f / 255, alpha = 0.12f)
val WarningDarkBg = Color(red = 245f / 255, green = 158f / 255, blue = 11f / 255, alpha = 0.12f)

// ========== Core semantic (legacy compat) ==========
val DarkBackground = BackgroundLight
val DarkSurface = BackgroundCardLight
val DarkText = ForegroundLight
val DarkTextGrey = ForegroundMutedLight

val AccentBlue = PrimaryBrand
val LightBlueBg = PrimarySubtle
val ButtonBg = PrimarySubtle
val GreenDot = Success
val UserBubbleStart = PrimaryBrand
val UserBubbleEnd = PrimaryHover

val BubbleBlue = PrimaryBrand
val BubbleBlueEnd = PrimaryHover
val CitationBg = PrimarySubtle
val BgGray = BackgroundLight
val CardWhite = BackgroundCardLight
val TextDark = ForegroundLight
val TextGrey = ForegroundMutedLight
val BarBg = BackgroundSecondaryLight

// ========== opedrgent风格扩展色板 (mapped to Doubao) ==========
// Primary accent purple kept as a distinct brand-adjacent color
val AccentPurple = Color(0xFF766AF6)

// Text hierarchy
val TextPrimary = ForegroundLight
val TextSecondary = ForegroundSecondaryLight
val TextTertiary = ForegroundMutedLight

// Surface / background hierarchy
val SurfaceLight = BackgroundSecondaryLight
val CardBackground = BackgroundMutedLight
val SurfaceElevated = BackgroundCardLight

// Border & divider
val DividerColor = BorderLightVariant
val InputBorder = BorderLight
val DisabledColor = ForegroundMutedLight

// Semantic status colors
val DangerRed = Error
val ErrorBackground = ErrorBg
val ErrorBorder = Error.copy(alpha = 0.2f)
val DeleteConfirmRed = Error

val SuccessGreen = Success

// Feature accent colors
val AccentOrange = Warning
val WarningColor = Warning

// Chip / tag colors
val ChipWarningBg = WarningBg
val ChipWarningText = Warning
val ChipSuccessBg = SuccessBg
val ChipSuccessText = Success

// Interview mode (intentional dark theme)
val InterviewDarkBg = Color(0xFF1A1A2E)
val InterviewPurple = Color(0xFF6C63FF)
val InterviewSurface = Color(0xFF2A2A4A)
val InterviewTextMuted = Color(0xFFB0B0CC)
val InterviewInputBg = Color(0xFF222233)
val InterviewBorder = Color(0xFF333355)
val InterviewDisabledText = Color(0xFF555566)

// Badge
val BadgeError = Error

// Sprout/Insight page (warm paper aesthetic)
val SproutBackground = Color(0xFFFAFAF5)
val SproutQuoteBg = Color(0xFFF5F0E8)
val SproutChipBg = Color(0xFFF0EAD6)
val SproutDivider = Color(0xFFE8E2D8)
val SproutSeedText = Color(0xFF6B6B6B)
val SproutMetaText = Color(0xFF9A9590)
val SproutSummaryStart = Color(0xFF34D399)
val SproutSummaryEnd = Color(0xFF059669)

// Recording mode card
val CoralRed = Error
val CoralLight = ErrorBg

// Dashboard card gradients (brand palette)
val GradientPurpleStart = Color(0xFF6a11cb)
val GradientPurpleEnd = Color(0xFF2575fc)
val GradientIndigoStart = Color(0xFF667eea)
val GradientIndigoEnd = Color(0xFF764ba2)
val GradientPinkStart = Color(0xFFf093fb)
val GradientPinkEnd = Color(0xFFf5576c)
val GradientCyanStart = Color(0xFF4facfe)
val GradientCyanEnd = Color(0xFF00f2fe)

// ========== Dark mode variants ==========
val BgGray_Dark = BackgroundDark
val CardWhite_Dark = BackgroundCardDark
val TextDark_Dark = ForegroundDark
val TextGrey_Dark = ForegroundMutedDark
val BarBg_Dark = BackgroundSecondaryDark
val SurfaceElevated_Dark = BackgroundMutedDark
val SurfaceLight_Dark = BackgroundSecondaryDark
val CardBackground_Dark = BackgroundCardDark
val BorderLight_Dark = BorderDark
val DividerColor_Dark = BorderDark

// Feature-specific dark variants
val CoralLight_Dark = ErrorDarkBg
val QuoteBg_Dark = WarningDarkBg
val ActionItemBg_Dark = PrimaryRing.copy(alpha = 0.12f)
val ChipWarningBg_Dark = WarningDarkBg
val ChipSuccessBg_Dark = SuccessDarkBg
val ErrorBackground_Dark = ErrorDarkBg
val WarningBg_Dark = WarningDarkBg

// Sprout dark variants
val SproutBackground_Dark = Color(0xFF1A1C1A)
val SproutQuoteBg_Dark = Color(0xFF2A2820)
val SproutChipBg_Dark = Color(0xFF2A2518)
val SproutDivider_Dark = Color(0xFF3A3830)
val SproutSeedText_Dark = Color(0xFFB0B0B0)
val SproutMetaText_Dark = Color(0xFF807A75)
val SproutSummaryStart_Dark = Color(0xFF1B6B4A)
val SproutSummaryEnd_Dark = Color(0xFF0D4030)

// Chat bubble dark variants
val UserBubbleStart_Dark = PrimaryRing
val UserBubbleEnd_Dark = PrimaryBrand
val CitationBg_Dark = PrimaryRing.copy(alpha = 0.25f)

// ========== Theme-aware composable helpers ==========
@Composable fun themeBgGray() = if (isSystemInDarkTheme()) BgGray_Dark else BgGray
@Composable fun themeCardWhite() = if (isSystemInDarkTheme()) CardWhite_Dark else CardWhite
@Composable fun themeTextDark() = if (isSystemInDarkTheme()) TextDark_Dark else TextDark
@Composable fun themeTextGrey() = if (isSystemInDarkTheme()) TextGrey_Dark else TextGrey
@Composable fun themeBarBg() = if (isSystemInDarkTheme()) BarBg_Dark else BarBg
@Composable fun themeSurfaceElevated() = if (isSystemInDarkTheme()) SurfaceElevated_Dark else SurfaceElevated
@Composable fun themeSurfaceLight() = if (isSystemInDarkTheme()) SurfaceLight_Dark else SurfaceLight
@Composable fun themeCardBackground() = if (isSystemInDarkTheme()) CardBackground_Dark else CardBackground
@Composable fun themeBorderLight() = if (isSystemInDarkTheme()) BorderLight_Dark else BorderLight
@Composable fun themeDividerColor() = if (isSystemInDarkTheme()) DividerColor_Dark else DividerColor
@Composable fun themeCoralLight() = if (isSystemInDarkTheme()) CoralLight_Dark else CoralLight
@Composable fun themeQuoteBg() = if (isSystemInDarkTheme()) QuoteBg_Dark else Color(0xFFFFFBEB)
@Composable fun themeActionItemBg() = if (isSystemInDarkTheme()) ActionItemBg_Dark else Color(0xFFF0F7FF)
@Composable fun themeChipWarningBg() = if (isSystemInDarkTheme()) ChipWarningBg_Dark else ChipWarningBg
@Composable fun themeChipSuccessBg() = if (isSystemInDarkTheme()) ChipSuccessBg_Dark else ChipSuccessBg
@Composable fun themeErrorBackground() = if (isSystemInDarkTheme()) ErrorBackground_Dark else ErrorBackground
@Composable fun themeWarningBg() = if (isSystemInDarkTheme()) WarningBg_Dark else WarningBg
@Composable fun themeSproutBackground() = if (isSystemInDarkTheme()) SproutBackground_Dark else SproutBackground
@Composable fun themeSproutQuoteBg() = if (isSystemInDarkTheme()) SproutQuoteBg_Dark else SproutQuoteBg
@Composable fun themeSproutChipBg() = if (isSystemInDarkTheme()) SproutChipBg_Dark else SproutChipBg
@Composable fun themeSproutDivider() = if (isSystemInDarkTheme()) SproutDivider_Dark else SproutDivider
@Composable fun themeSproutSeedText() = if (isSystemInDarkTheme()) SproutSeedText_Dark else SproutSeedText
@Composable fun themeSproutMetaText() = if (isSystemInDarkTheme()) SproutMetaText_Dark else SproutMetaText
@Composable fun themeSproutSummaryStart() = if (isSystemInDarkTheme()) SproutSummaryStart_Dark else SproutSummaryStart
@Composable fun themeSproutSummaryEnd() = if (isSystemInDarkTheme()) SproutSummaryEnd_Dark else SproutSummaryEnd

// ========== Doubao semantic theme helpers (dark aware) ==========
@Composable fun themePrimary() = PrimaryBrand
@Composable fun themePrimaryRing() = PrimaryRing
@Composable fun themePrimarySubtle() = if (isSystemInDarkTheme()) PrimaryRing.copy(alpha = 0.18f) else PrimarySubtle
@Composable fun themeSuccess() = Success
@Composable fun themeWarning() = Warning
@Composable fun themeError() = Error
@Composable fun themeBackgroundSecondary() = if (isSystemInDarkTheme()) BackgroundSecondaryDark else BackgroundSecondaryLight
@Composable fun themeBackgroundMuted() = if (isSystemInDarkTheme()) BackgroundMutedDark else BackgroundMutedLight
@Composable fun themeForeground() = if (isSystemInDarkTheme()) ForegroundDark else ForegroundLight
@Composable fun themeForegroundSecondary() = if (isSystemInDarkTheme()) ForegroundSecondaryDark else ForegroundSecondaryLight
@Composable fun themeForegroundMuted() = if (isSystemInDarkTheme()) ForegroundMutedDark else ForegroundMutedLight
@Composable fun themeBorder() = if (isSystemInDarkTheme()) BorderDark else BorderLight

// ========== Gradient theme helpers (dark aware) ==========
@Composable fun themeGradientInterview(): List<Color> =
    if (isSystemInDarkTheme()) listOf(Color(0xFF7C3AED), Color(0xFF9F67FF))
    else listOf(Color(0xFF5856D6), Color(0xFF7C3AED))

@Composable fun themeGradientEditor(): List<Color> =
    if (isSystemInDarkTheme()) listOf(Color(0xFF818CF8), Color(0xFFA5B4FC))
    else listOf(Color(0xFF6366F1), Color(0xFF818CF8))

@Composable fun themeGradientVoice(): List<Color> =
    if (isSystemInDarkTheme()) listOf(Color(0xFFF97316), Color(0xFFFB923C))
    else listOf(Color(0xFFEF4444), Color(0xFFF97316))

@Composable fun themeGradientSprout(): List<Color> =
    if (isSystemInDarkTheme()) listOf(Color(0xFF34D399), Color(0xFF6EE7B7))
    else listOf(Color(0xFF22C55E), Color(0xFF34D399))
