package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.customColors

/**
 * 引用徽章 — 将 [S1]、[S2] 等引用标记渲染为小号药丸样式。
 *
 * 使用 accentBlue 半透明底色 + accentBlue 文字，与正文形成视觉区分；
 * 提供 [onClick] 时表现为可点击芯片。
 */
@Composable
fun CitationPill(
    text: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = MaterialTheme.customColors.accentBlue
    val base = modifier
        .clip(ShapeTokens.extraSmallShape)
        .background(accentColor.copy(alpha = 0.12f))
        .padding(horizontal = SpacingTokens.xs, vertical = SpacingTokens.xxs)

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = accentColor,
        modifier = if (onClick != null) base.clickable { onClick() } else base,
    )
}
