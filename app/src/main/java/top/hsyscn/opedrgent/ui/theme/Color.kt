package top.hsyscn.opedrgent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ========== Material tonal palette tokens ==========
val Blue80 = Color(0xFFAAC4FF)
val BlueGrey80 = Color(0xFFBCC7DC)
val Teal80 = Color(0xFFA0D4C8)

val Blue40 = Color(0xFF2B68DE)
val BlueGrey40 = Color(0xFF5A6A7E)
val Teal40 = Color(0xFF4A9A8A)

// ========== Core semantic (legacy compat) ==========
val DarkBackground = Color(0xFFF5F5F6)    // 得到大脑背景色
val DarkSurface = Color(0xFFFCFCFC)        // 得到大脑卡片背景
val DarkText = Color(0xFF1D2129)           // 得到大脑主文字
val DarkTextGrey = Color(0xFF8A8F99)       // 得到大脑次要文字

val AccentBlue = Color(0xFF08A1F9)         // 得到大脑链接蓝
val LightBlueBg = Color(0xFFD1D7FE)
val ButtonBg = Color(0xFFEDF2FE)
val GreenDot = Color(0xFF2B7F47)
val UserBubbleStart = Color(0xFF2B68DE)
val UserBubbleEnd = Color(0xFF194CF0)

val BubbleBlue = Color(0xFF2B68DE)
val BubbleBlueEnd = Color(0xFF194CF0)
val CitationBg = Color(0xFFD1D7FE)
val BgGray = Color(0xFFF5F5F6)             // 得到大脑背景色
val CardWhite = Color(0xFFFCFCFC)          // 得到大脑卡片背景
val TextDark = Color(0xFF1D2129)           // 得到大脑主文字
val TextGrey = Color(0xFF8A8F99)           // 得到大脑次要文字
val BarBg = Color(0xFFF5F5F6)              // 得到大脑背景色

// ========== 得到大脑风格扩展色板 ==========
// Primary accent purple (得到大脑 brand color)
val AccentPurple = Color(0xFF766AF6)

// Text hierarchy
val TextPrimary = Color(0xFF1D2129)       // 主文字
val TextSecondary = Color(0xFF8A8F99)     // 次要文字
val TextTertiary = Color(0xFF4E5969)      // 辅助文字

// Surface / background hierarchy
val SurfaceLight = Color(0xFFF7F8FA)      // 分区背景
val CardBackground = Color(0xFFF5F7FA)    // 卡片/容器背景
val SurfaceElevated = Color(0xFFFAFAFA)   // 浮层/输入区背景

// Border & divider
val BorderLight = Color(0xFFE5E6EA)       // 轻边框
val DividerColor = Color(0xFFF0F0F0)      // 分割线
val InputBorder = Color(0xFFE0E0E0)       // 输入框边框
val DisabledColor = Color(0xFFBDBDBD)     // 禁用态

// Semantic status colors
val DangerRed = Color(0xFFC04040)         // 危险操作（删除图标）
val ErrorBackground = Color(0xFFFFF3F3)   // 错误背景
val ErrorBorder = Color(0xFFFFCCCC)       // 错误边框
val DeleteConfirmRed = Color(0xFFCC3333)  // 删除确认按钮

val SuccessGreen = Color(0xFF4CAF50)      // 成功状态

// Feature accent colors
val AccentOrange = Color(0xFFE67E22)      // 标签/高亮橙
val WarningBg = Color(0xFFFFF8E1)         // 警告背景
val WarningColor = Color(0xFFFF8F00)      // 警告文字/图标

// Chip / tag colors
val ChipWarningBg = Color(0xFFFFF3E0)
val ChipWarningText = Color(0xFFE65100)
val ChipSuccessBg = Color(0xFFE8F5E9)
val ChipSuccessText = Color(0xFF2E7D32)

// Interview mode (intentional dark theme)
val InterviewDarkBg = Color(0xFF1A1A2E)
val InterviewPurple = Color(0xFF6C63FF)
val InterviewSurface = Color(0xFF2A2A4A)
val InterviewTextMuted = Color(0xFFB0B0CC)
val InterviewInputBg = Color(0xFF222233)
val InterviewBorder = Color(0xFF333355)
val InterviewDisabledText = Color(0xFF555566)

// Badge
val BadgeError = Color(0xFFFF4444)

// Sprout/Insight page (warm paper aesthetic)
val SproutBackground = Color(0xFFFAFAF5)     // 暖白纸张底色
val SproutQuoteBg = Color(0xFFF5F0E8)        // 引用块暖灰背景
val SproutChipBg = Color(0xFFF0EAD6)         // 标签暖色背景
val SproutDivider = Color(0xFFE8E2D8)        // 暖色分割线（加深以确保可见）
val SproutSeedText = Color(0xFF6B6B6B)       // 种子引文（比 #777 深，确保可读）
val SproutMetaText = Color(0xFF9A9590)       // 元信息（日期/图标，比 #888 深）
val SproutSummaryStart = Color(0xFF34D399)   // 摘要渐变起始色
val SproutSummaryEnd = Color(0xFF059669)     // 摘要渐变结束色

// Recording mode card
val CoralRed = Color(0xFFFF5A5A)
val CoralLight = Color(0xFFFFEAEA)

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
val BgGray_Dark = Color(0xFF121212)
val CardWhite_Dark = Color(0xFF1E1E1E)
val TextDark_Dark = Color(0xFFE8E8E8)
val TextGrey_Dark = Color(0xFF9E9E9E)
val BarBg_Dark = Color(0xFF1A1A1A)
val SurfaceElevated_Dark = Color(0xFF252525)
val SurfaceLight_Dark = Color(0xFF1C1C1C)
val CardBackground_Dark = Color(0xFF1E1E1E)
val BorderLight_Dark = Color(0xFF333333)
val DividerColor_Dark = Color(0xFF2A2A2A)
// Feature-specific dark variants
val CoralLight_Dark = Color(0xFF3D1F1F)       // 录音模式选中背景
val QuoteBg_Dark = Color(0xFF3D3520)          // 金句卡片背景
val ActionItemBg_Dark = Color(0xFF1A2A3D)     // 待办卡片背景
val ChipWarningBg_Dark = Color(0xFF3D2E1A)
val ChipSuccessBg_Dark = Color(0xFF1A3D1F)
val ErrorBackground_Dark = Color(0xFF3D1A1A)
val WarningBg_Dark = Color(0xFF3D3520)
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
val UserBubbleStart_Dark = Color(0xFF5B8DEF)
val UserBubbleEnd_Dark = Color(0xFF3B6BD6)
val CitationBg_Dark = Color(0xFF3D4660)

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
