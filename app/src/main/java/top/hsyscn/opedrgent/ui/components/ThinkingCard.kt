package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

/**
 * 思考过程卡片。
 *
 * 展示模型推理（reasoning）内容的可折叠卡片，与普通消息气泡形成视觉层级：
 * - 顶部：脉冲渐变指示器 + "思考过程" 标签 + 展开/收起按钮
 * - 展开时：可滚动区域显示思考正文
 * - 收起时：仅显示顶部
 *
 * 背景使用 primaryContainer -> surfaceContainerLow 的柔和渐变，区别于普通气泡，
 * 以建立"思考中"与"正式回复"之间的视觉层级。
 *
 * @param thinkingText 思考过程文本
 * @param isComplete 是否已完成思考（影响指示器动画：进行中脉冲、完成则静止）
 * @param modifier 修饰符
 * @param initiallyExpanded 初始是否展开
 */
@Composable
fun ThinkingCard(
    thinkingText: String,
    isComplete: Boolean,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = true,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    val colorScheme = MaterialTheme.colorScheme
    // 思考卡片使用与普通气泡不同的渐变背景，建立视觉层级
    val cardBackground = Brush.linearGradient(
        colors = listOf(
            colorScheme.primaryContainer.copy(alpha = 0.22f),
            colorScheme.surfaceContainerLow.copy(alpha = 0.65f),
        ),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShapeTokens.mediumShape)
            .background(cardBackground)
            .animateContentSize(),
    ) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        ) {
            ThinkingPulseIndicator(isComplete = isComplete)
            Text(
                text = stringResource(R.string.thinking_card_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Expanded content (scrollable)
        if (expanded && thinkingText.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = SpacingTokens.md,
                        end = SpacingTokens.md,
                        bottom = SpacingTokens.md,
                    )
                    .clip(ShapeTokens.smallShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f))
                    .heightIn(max = SizeTokens.expandedContentMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(SpacingTokens.md),
            ) {
                MarkdownText(
                    text = thinkingText.trim(),
                    maxChars = Int.MAX_VALUE,
                )
            }
        }
    }
}

/**
 * 思考状态指示器：使用脉冲渐变。
 *
 * - 进行中：渐变圆持续脉冲（透明度循环），暗示"思考中"
 * - 完成：固定渐变圆，停止动画
 */
@Composable
private fun ThinkingPulseIndicator(isComplete: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking_pulse")

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    val indicatorBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
        ),
    )

    Box(
        modifier = Modifier
            .size(12.dp)
            .graphicsLayer { alpha = if (isComplete) 1f else pulse }
            .clip(CircleShape)
            .background(indicatorBrush),
    )
}
