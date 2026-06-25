package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

@Composable
fun MessageBodyThinking(
    thinkingText: String,
    isComplete: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f))
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Crossfade(targetState = expanded, label = "thinking_crossfade") { isExp ->
                if (isExp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ExpandLess,
                            contentDescription = "收起思考",
                            modifier = Modifier.size(18.dp),
                            tint = themeTextGrey(),
                        )
                        Text(
                            text = if (isComplete) "思考完成" else "深度思考中",
                            style = MaterialTheme.typography.bodySmall,
                            color = themeTextGrey(),
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThinkingDotsIndicator(isComplete = isComplete)
                        Text(
                            text = if (isComplete) "思考完成" else "深度思考中",
                            style = MaterialTheme.typography.bodySmall,
                            color = themeTextGrey(),
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = "展开思考",
                            modifier = Modifier.size(18.dp),
                            tint = themeTextGrey(),
                        )
                    }
                }
            }
        }

        if (expanded && thinkingText.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(14.dp)
                    .heightIn(max = 400.dp),
            ) {
                MarkdownText(
                    text = thinkingText.trim(),
                    maxChars = Int.MAX_VALUE,
                )
            }
        }
    }
}

@Composable
private fun ThinkingDotsIndicator(isComplete: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking_dots")

    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot1",
    )

    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing, delayMillis = 150),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot2",
    )

    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing, delayMillis = 300),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot3",
    )

    val baseColor = if (isComplete) MaterialTheme.colorScheme.primary else AccentBlue

    Canvas(modifier = Modifier.size(24.dp, 10.dp)) {
        val radius = 3.dp.toPx()
        val spacing = 8.dp.toPx()

        drawCircle(
            color = baseColor.copy(alpha = if (isComplete) 1f else dot1Alpha),
            radius = radius,
            center = Offset(radius, size.height / 2),
        )
        drawCircle(
            color = baseColor.copy(alpha = if (isComplete) 1f else dot2Alpha),
            radius = radius,
            center = Offset(spacing + radius, size.height / 2),
        )
        drawCircle(
            color = baseColor.copy(alpha = if (isComplete) 1f else dot3Alpha),
            radius = radius,
            center = Offset(spacing * 2 + radius, size.height / 2),
        )
    }
}
