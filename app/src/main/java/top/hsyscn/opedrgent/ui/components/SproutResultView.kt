package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.ui.SproutingState

@Composable
fun SproutResultView(
    markdownReport: String,
    sproutingState: SproutingState,
    onCopy: () -> Unit,
    onContinueChat: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (markdownReport.isEmpty() && sproutingState == SproutingState.IDLE) return

    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Spa, contentDescription = null, tint = Color(0xFF4CAF50))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (sproutingState) {
                        SproutingState.IDLE -> "🌱 发芽报告"
                        SproutingState.PHASE1 -> "🌱 正在提取种子..."
                        SproutingState.PHASE2 -> "🔗 正在跨领域关联..."
                        SproutingState.PHASE3 -> "✨ 正在生成 Aha 洞察..."
                        SproutingState.PHASE4 -> "💡 正在生成金句回响..."
                        SproutingState.DONE -> "🌱 发芽完成"
                        SproutingState.ERROR -> "❌ 发芽失败"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }

            Spacer(Modifier.height(12.dp))

            if (sproutingState in listOf(SproutingState.PHASE1, SproutingState.PHASE2, SproutingState.PHASE3, SproutingState.PHASE4)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(120.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在思考中...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (sproutingState == SproutingState.DONE && markdownReport.isNotEmpty()) {
                MarkdownText(text = markdownReport, maxChars = Int.MAX_VALUE, modifier = Modifier.verticalScroll(rememberScrollState()))

                Spacer(Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.Start,
                ) {
                    OutlinedButton(onClick = onCopy) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("复制全文")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onContinueChat) {
                        Icon(Icons.Default.Chat, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("继续追问")
                    }
                }
            } else if (sproutingState == SproutingState.ERROR) {
                Text("发芽过程中出现错误，请重试", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
