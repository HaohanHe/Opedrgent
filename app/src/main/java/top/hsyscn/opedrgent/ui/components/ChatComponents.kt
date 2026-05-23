package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.CardWhite
import top.hsyscn.opedrgent.ui.theme.TextDark

private val BubbleBlue = Color(0xFF2B68DE)
private val BubbleBlueEnd = Color(0xFF194CF0)
private val CitationBg = Color(0xFFD1D7FE)

@Composable
fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .width(220.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Brush.horizontalGradient(listOf(BubbleBlue, BubbleBlueEnd)))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
fun AIMessageCard(
    message: ChatMessage,
    onSpeak: (() -> Unit)?,
    isSpeaking: Boolean,
    clipboard: ClipboardManager,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(11.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (message.reasoningParts.isNotEmpty()) {
                val reasoningText = message.reasoningParts.joinToString("\n") { it.text }
                MessageBodyThinking(
                    thinkingText = reasoningText,
                    isComplete = true,
                )
            }

            if (message.toolParts.isNotEmpty()) {
                message.toolParts.forEach { tp ->
                    ToolStatusRow(toolPart = tp)
                }
            }

            if (message.questionPart != null) {
                QuestionCard(
                    question = message.questionPart!!,
                    onAnswer = {},
                    onDismiss = {},
                    readonly = true,
                )
            }

            if (message.content.isNotBlank()) {
                MarkdownText(text = message.content, maxChars = 900)
            }

            val sources = extractSources(message.content)
            if (sources.isNotEmpty()) {
                SourceCitations(sources = sources)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(onClick = { }, modifier = Modifier.size(28.dp)) {
                            Text("\uD83D\uDC4D", fontSize = 14.sp)
                        }
                        IconButton(onClick = { }, modifier = Modifier.size(28.dp)) {
                            Text("\uD83D\uDC4E", fontSize = 14.sp)
                        }
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(message.content)) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Text("\uD83D\uDCCB", fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun extractSources(content: String): List<Pair<String, String>> {
    val pattern = Regex("""\[(\d+)\]\s*(https?://\S+)""")
    return pattern.findAll(content).map { it.groupValues[1] to it.groupValues[2] }.toList()
}

@Composable
fun SourceCitations(sources: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        sources.forEach { (index, url) ->
            Card(
                shape = RoundedCornerShape(3.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(CitationBg),
                    width = 1.dp,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .background(CitationBg, RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(index, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AccentBlue)
                    }
                    Text(
                        text = runCatching { java.net.URL(url).host }.getOrDefault(url.take(30)),
                        fontSize = 12.sp,
                        color = AccentBlue,
                    )
                }
            }
        }
    }
}
