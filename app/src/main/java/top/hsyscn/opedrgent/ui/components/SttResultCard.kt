package top.hsyscn.opedrgent.ui.components

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Transcribe
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
) {
    if (result == null && error == null) return

    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Transcribe, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (error != null) "转录失败" else "转录完成",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }

            Spacer(Modifier.height(8.dp))

            if (error != null) {
                Text(text = error, color = MaterialTheme.colorScheme.error)
            } else {
                Text(
                    text = result!!.text.ifEmpty { "[空结果]" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 200.dp),
                )

                Text(
                    text = buildString {
                        append("时长: ${AudioProcessor.formatDuration(result.durationMs)} | ")
                        append("字数: ${result.text.length} | ")
                        append("引擎: ${result.engineType.name}")
                        if (result.modelUsed.isNotEmpty()) {
                            append(" (${result.modelUsed})")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.Start,
                ) {
                    OutlinedButton(onClick = onCopy) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("复制")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onSendToLlm) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("发送给 AI 分析")
                    }
                }
            }
        }
    }
}
