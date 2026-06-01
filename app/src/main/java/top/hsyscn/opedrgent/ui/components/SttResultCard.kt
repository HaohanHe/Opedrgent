package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.stt.AudioProcessor
import top.hsyscn.opedrgent.stt.SttResult

@Composable
fun SttResultCard(
    result: SttResult?,
    error: String?,
    onCopy: () -> Unit,
    onSendToLlm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (result == null && error == null) return

    val isSuccess = error == null && result != null

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(
            initialOffsetY = { it / 3 },
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        ) + fadeIn(),
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentDescription = "语音转录结果卡片" }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                ResultTitleBar(isSuccess = isSuccess, onDismiss = onDismiss)

                Spacer(Modifier.height(12.dp))

                if (error != null) {
                    ErrorContent(error = error)
                } else if (result != null) {
                    SuccessContent(result = result)
                }

                Spacer(Modifier.height(12.dp))

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Spacer(Modifier.height(12.dp))

                ActionButtons(
                    isSuccess = isSuccess,
                    isLoading = remember { mutableStateOf(false) },
                    onCopy = onCopy,
                    onSendToLlm = onSendToLlm,
                )
            }
        }
    }
}

@Composable
private fun ResultTitleBar(isSuccess: Boolean, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = if (isSuccess) "转录成功" else "转录失败",
            tint = if (isSuccess)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (isSuccess) "转录结果" else "转录失败",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(36.dp)
                .semantics { contentDescription = "关闭转录结果" },
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ErrorContent(error: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "可能原因：文件格式不支持、文件损坏或音频内容为空",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SuccessContent(result: SttResult) {
    var statsExpanded by rememberSaveable { mutableStateOf(false) }
    val charCount by remember(result.text) { mutableIntStateOf(result.text.length) }

    Column {
        Text(
            text = result.text.ifEmpty { "[空结果]" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState())
                .semantics { contentDescription = "转录文本，共 ${charCount} 个字" },
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "$charCount 字",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.End),
        )

        Spacer(Modifier.height(8.dp))

        StatsBar(
            result = result,
            expanded = statsExpanded,
            onToggleExpand = { statsExpanded = !statsExpanded },
        )

        AnimatedVisibility(visible = statsExpanded) {
            SegmentList(segments = result.segments)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatsBar(
    result: SttResult,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val statsText = remember(result) {
        buildString {
            append("时长 ${AudioProcessor.formatDuration(result.durationMs)}")
            append("  \u00B7  ")
            append("${result.text.length} 字")
            append("  \u00B7  ")
            append(result.engineType.name)
            if (result.modelUsed.isNotEmpty()) {
                append(" (${result.modelUsed})")
            }
            if (result.processingTimeMs > 0) {
                append("  \u00B7  ")
                append("${result.processingTimeMs}ms")
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        onClick = onToggleExpand,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .semantics { contentDescription = "统计信息：$statsText。${if (expanded) "已展开" else "点击展开详情"}" },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = statsText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起详情" else "展开详情",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SegmentList(segments: List<SttSegment>) {
    if (segments.isEmpty()) return

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
    ) {
        segments.forEachIndexed { index, segment ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "第 ${index + 1} 段，置信度 ${(segment.confidence * 100).toInt()}%" },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "${segment.startTimeMs / 1000}s - ${segment.endTimeMs / 1000}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(90.dp),
                    )
                    Text(
                        text = segment.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(segment.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            segment.confidence >= 0.9f -> MaterialTheme.colorScheme.primary
                            segment.confidence >= 0.7f -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButtons(
    isSuccess: Boolean,
    isLoading: androidx.compose.runtime.MutableState<Boolean>,
    onCopy: () -> Unit,
    onSendToLlm: () -> Unit,
) {
    var copyFeedbackShown by remember { mutableStateOf(false) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedButton(
            onClick = {
                if (!isLoading.value) {
                    onCopy()
                    copyFeedbackShown = true
                }
            },
            enabled = isSuccess && !isLoading.value,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .semantics { contentDescription = "复制转录文本" },
        ) {
            if (copyFeedbackShown) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("已复制", fontWeight = FontWeight.Medium)
            } else {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("复制", fontWeight = FontWeight.Medium)
            }
        }

        Button(
            onClick = {
                if (!isLoading.value) {
                    isLoading.value = true
                    onSendToLlm()
                }
            },
            enabled = isSuccess && !isLoading.value,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .semantics { contentDescription = "发送给 AI 分析" },
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("发送给 AI 分析", fontWeight = FontWeight.Medium)
        }
    }
}
