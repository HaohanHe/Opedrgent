package top.hsyscn.opedrgent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 业务语义颜色集合。
 *
 * 这些颜色在 Material3 colorScheme 中没有直接对应语义，但产品里大量使用（如发芽页暖色、标签色、状态色）。
 * 通过 CompositionLocal 提供，并在浅色/深色主题下自动切换。
 */
@Immutable
data class CustomColors(
    val accentBlue: Color = AccentBlue,
    val accentOrange: Color = AccentOrange,
    val successGreen: Color = SuccessGreen,
    val dangerRed: Color = DangerRed,
    val deleteConfirmRed: Color = DeleteConfirmRed,

    val sproutBackground: Color = SproutBackground,
    val sproutQuoteBg: Color = SproutQuoteBg,
    val sproutChipBg: Color = SproutChipBg,
    val sproutDivider: Color = SproutDivider,
    val sproutSeedText: Color = SproutSeedText,
    val sproutMetaText: Color = SproutMetaText,
    val sproutSummaryStart: Color = SproutSummaryStart,
    val sproutSummaryEnd: Color = SproutSummaryEnd,

    val coralRed: Color = CoralRed,
    val coralLight: Color = CoralLight,

    val chipWarningBg: Color = ChipWarningBg,
    val chipWarningText: Color = ChipWarningText,
    val chipSuccessBg: Color = ChipSuccessBg,
    val chipSuccessText: Color = ChipSuccessText,

    val errorBackground: Color = ErrorBackground,
    val errorBorder: Color = ErrorBorder,
    val warningBg: Color = WarningBg,
    val warningColor: Color = WarningColor,

    val quoteBg: Color = Color(0xFFFFFBEB),
    val actionItemBg: Color = Color(0xFFF0F7FF),

    val userBubbleStart: Color = UserBubbleStart,
    val userBubbleEnd: Color = UserBubbleEnd,
    val citationBg: Color = CitationBg,
)

val LightCustomColors = CustomColors()

val DarkCustomColors = CustomColors(
    accentBlue = Blue80,
    accentOrange = AccentOrange,
    successGreen = SuccessGreen,
    dangerRed = DangerRed,
    deleteConfirmRed = DeleteConfirmRed,

    sproutBackground = SproutBackground_Dark,
    sproutQuoteBg = SproutQuoteBg_Dark,
    sproutChipBg = SproutChipBg_Dark,
    sproutDivider = SproutDivider_Dark,
    sproutSeedText = SproutSeedText_Dark,
    sproutMetaText = SproutMetaText_Dark,
    sproutSummaryStart = SproutSummaryStart_Dark,
    sproutSummaryEnd = SproutSummaryEnd_Dark,

    coralRed = CoralRed,
    coralLight = CoralLight_Dark,

    chipWarningBg = ChipWarningBg_Dark,
    chipWarningText = ChipWarningText,
    chipSuccessBg = ChipSuccessBg_Dark,
    chipSuccessText = ChipSuccessText,

    errorBackground = ErrorBackground_Dark,
    errorBorder = ErrorBorder,
    warningBg = WarningBg_Dark,
    warningColor = WarningColor,

    quoteBg = QuoteBg_Dark,
    actionItemBg = ActionItemBg_Dark,

    userBubbleStart = UserBubbleStart_Dark,
    userBubbleEnd = UserBubbleEnd_Dark,
    citationBg = CitationBg_Dark,
)

val LocalCustomColors = compositionLocalOf { LightCustomColors }

/**
 * 在 Composable 中读取当前主题下的业务语义颜色。
 */
val MaterialTheme.customColors: CustomColors
    @Composable
    @ReadOnlyComposable
    get() = LocalCustomColors.current
