package top.hsyscn.opedrgent.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 思考过程展示组件（向后兼容包装）。
 *
 * 该组件委托给 [ThinkingCard]，保持原有公共 API 不变，调用方无需改动。
 *
 * @param thinkingText 思考过程文本
 * @param isComplete 是否已完成思考
 * @param modifier 修饰符
 */
@Composable
fun MessageBodyThinking(
    thinkingText: String,
    isComplete: Boolean,
    modifier: Modifier = Modifier,
) {
    ThinkingCard(
        thinkingText = thinkingText,
        isComplete = isComplete,
        modifier = modifier,
    )
}
