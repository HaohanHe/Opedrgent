package top.hsyscn.opedrgent.ui.theme

import androidx.compose.ui.graphics.Color

// ========== Material tonal palette tokens ==========
val Blue80 = Color(0xFFAAC4FF)
val BlueGrey80 = Color(0xFFBCC7DC)
val Teal80 = Color(0xFFA0D4C8)

val Blue40 = Color(0xFF2B68DE)
val BlueGrey40 = Color(0xFF5A6A7E)
val Teal40 = Color(0xFF4A9A8A)

// ========== Core semantic (legacy compat) ==========
val DarkBackground = Color(0xFFF3F3F3)
val DarkSurface = Color(0xFFFFFFFF)
val DarkText = Color(0xFF1E242A)
val DarkTextGrey = Color(0xFF7D7984)

val AccentBlue = Color(0xFF1449E2)
val LightBlueBg = Color(0xFFD1D7FE)
val ButtonBg = Color(0xFFEDF2FE)
val GreenDot = Color(0xFF2B7F47)
val UserBubbleStart = Color(0xFF2B68DE)
val UserBubbleEnd = Color(0xFF194CF0)

val BubbleBlue = Color(0xFF2B68DE)
val BubbleBlueEnd = Color(0xFF194CF0)
val CitationBg = Color(0xFFD1D7FE)
val BgGray = Color(0xFFF3F3F3)
val CardWhite = Color(0xFFFFFFFF)
val TextDark = Color(0xFF1E242A)
val TextGrey = Color(0xFF7D7984)
val BarBg = Color(0xFFF7F7F7)

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

// Badge
val BadgeError = Color(0xFFFF4444)

// Sprout/Insight page (warm paper aesthetic)
val SproutBackground = Color(0xFFFAFAF5)     // 暖白纸张底色
val SproutQuoteBg = Color(0xFFF5F0E8)        // 引用块暖灰背景
val SproutChipBg = Color(0xFFF0EAD6)         // 标签暖色背景
val SproutDivider = Color(0xFFE8E2D8)        // 暖色分割线（加深以确保可见）
val SproutSeedText = Color(0xFF6B6B6B)       // 种子引文（比 #777 深，确保可读）
val SproutMetaText = Color(0xFF9A9590)       // 元信息（日期/图标，比 #888 深）
