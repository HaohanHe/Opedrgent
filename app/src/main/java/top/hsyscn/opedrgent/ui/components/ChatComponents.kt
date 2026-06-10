package top.hsyscn.opedrgent.ui.components

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.MessagePart
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.CardWhite
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey

private val BubbleBlue = Color(0xFF2B68DE)
private val BubbleBlueEnd = Color(0xFF194CF0)
private val CitationBg = Color(0xFFD1D7FE)

@Composable
fun UserBubble(
    text: String,
    clipboard: ClipboardManager? = null,
    onUndo: (() -> Unit)? = null,
    audioClips: List<MessagePart.AudioClip> = emptyList(),
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            // 音频卡片（用户发送的音频消息）
            audioClips.forEach { audioClip ->
                AudioClipPlayerCard(
                    audioClip = audioClip,
                    modifier = Modifier.width(280.dp),
                )
                Spacer(Modifier.height(6.dp))
            }

            Box(
                modifier = Modifier
                    .width(220.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .background(Brush.horizontalGradient(listOf(BubbleBlue, BubbleBlueEnd)))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { showMenu = true },
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = text,
                        color = Color.White,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                if (clipboard != null) {
                    DropdownMenuItem(
                        text = { Text("复制") },
                        onClick = {
                            clipboard.setText(AnnotatedString(text))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            showMenu = false
                        },
                    )
                }
                if (onUndo != null) {
                    DropdownMenuItem(
                        text = { Text("撤回") },
                        onClick = {
                            onUndo()
                            showMenu = false
                        },
                    )
                }
            }
        } // end Column
    } // end Row
}

/**
 * 音频消息播放卡片组件。
 * 对标 Gallery ChatHistory AudioMessageProto 的 UI 展示。
 */
@Composable
fun AudioClipPlayerCard(
    audioClip: MessagePart.AudioClip,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var currentPosition by remember { mutableLongStateOf(0L) }

    val mediaPlayer = remember {
        MediaPlayer().apply {
            setOnCompletionListener {
                isPlaying = false
                progress = 1f
                currentPosition = audioClip.durationMs
                seekTo(0)
            }
            setOnErrorListener { _, _, _ ->
                isPlaying = false
                Toast.makeText(context, "音频播放失败", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    DisposableEffect(audioClip.filePath) {
        try {
            mediaPlayer.setDataSource(audioClip.filePath)
            mediaPlayer.prepare()
        } catch (e: Exception) {
            Toast.makeText(context, "无法加载音频文件", Toast.LENGTH_SHORT).show()
        }
        onDispose {
            try {
                mediaPlayer.stop()
                mediaPlayer.release()
            } catch (_: Exception) {}
        }
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    if (isPlaying) {
                        mediaPlayer.pause()
                        isPlaying = false
                    } else {
                        mediaPlayer.start()
                        isPlaying = true
                    }
                },
                modifier = Modifier
                    .size(44.dp)
                    .background(AccentBlue, CircleShape),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = Color.White,
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                val totalDuration = if (audioClip.durationMs > 0) audioClip.durationMs else
                    (if (mediaPlayer.duration > 0) mediaPlayer.duration.toLong() else 0L)

                LinearProgressIndicator(
                    progress = { progress },
                    color = AccentBlue,
                    trackColor = AccentBlue.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(4.dp))

                Row {
                    Text(
                        text = formatDuration(currentPosition.takeIf { it > 0 } ?: 0L),
                        fontSize = 11.sp,
                        color = TextGrey,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "/ ${formatDuration(totalDuration)}",
                        fontSize = 11.sp,
                        color = TextGrey.copy(alpha = 0.7f),
                    )
                    if (audioClip.transcript.isNotBlank()) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "有转录",
                            fontSize = 11.sp,
                            color = AccentBlue,
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return "${seconds}s"
}

@Composable
fun AIMessageCard(
    message: ChatMessage,
    onSpeak: (() -> Unit)?,
    isSpeaking: Boolean,
    clipboard: ClipboardManager,
    onUndo: (() -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showMenu = true },
                ),
            shape = RoundedCornerShape(11.dp),
            colors = CardDefaults.cardColors(containerColor = CardWhite),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 音频消息卡片（如果有）
                if (message.hasAudio) {
                    message.audioClips.forEach { audioClip ->
                        AudioClipPlayerCard(audioClip = audioClip)
                    }
                }

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

                if (message.textContent.isNotBlank()) {
                    MarkdownText(text = message.textContent, maxChars = 900)
                }

                val sources = extractSources(message.textContent)
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
                                onClick = {
                                    clipboard.setText(AnnotatedString(message.textContent))
                                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Text("\uD83D\uDCCB", fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        // 长按菜单：复制 / 撤回
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text("复制") },
                onClick = {
                    clipboard.setText(AnnotatedString(message.textContent))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    showMenu = false
                },
            )
            if (onUndo != null) {
                DropdownMenuItem(
                    text = { Text("撤回") },
                    onClick = {
                        onUndo()
                        showMenu = false
                    },
                )
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
