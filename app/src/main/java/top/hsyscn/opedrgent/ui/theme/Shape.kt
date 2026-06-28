package top.hsyscn.opedrgent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 统一圆角 token。
 *
 * 使用规则：
 * - extraSmall: 小标签、徽章、芯片
 * - small: 按钮、输入框、小卡片
 * - medium: 标准卡片、底部弹窗、对话框
 * - large: 大卡片、悬浮面板
 * - extraLarge: 全屏底部弹窗、大模态框
 */
object ShapeTokens {
    val extraSmall = 6.dp
    val small = 12.dp
    val medium = 16.dp
    val large = 20.dp
    val extraLarge = 28.dp

    // 特殊用途
    val tag = 4.dp
    val icon = 8.dp
    val pill = 9999.dp

    val extraSmallShape = RoundedCornerShape(extraSmall)
    val smallShape = RoundedCornerShape(small)
    val mediumShape = RoundedCornerShape(medium)
    val largeShape = RoundedCornerShape(large)
    val extraLargeShape = RoundedCornerShape(extraLarge)
    val tagShape = RoundedCornerShape(tag)
    val iconShape = RoundedCornerShape(icon)
    val pillShape = RoundedCornerShape(pill)
}

/**
 * Material3 Shapes，供 MaterialTheme.shapes 使用。
 */
val AppShapes = Shapes(
    extraSmall = ShapeTokens.extraSmallShape,
    small = ShapeTokens.smallShape,
    medium = ShapeTokens.mediumShape,
    large = ShapeTokens.largeShape,
    extraLarge = ShapeTokens.extraLargeShape,
)
