package top.hsyscn.opedrgent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.NoteRepository
import top.hsyscn.opedrgent.ui.components.LocalFeedbackController
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.customColors
import java.io.File
import java.io.FileOutputStream

/**
 * 笔记分享屏幕。
 *
 * 支持：
 * - AI 风格转换（小红书/公众号/朋友圈）
 * - 分享到微信/朋友圈
 * - 分享到小红书
 * - 复制链接/文本
 * - 生成分享图片
 * - 导出为HTML/Markdown
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteShareScreen(
    repository: NoteRepository,
    noteId: Long,
    onBack: () -> Unit,
    aiConvertedContent: String? = null,
    isConverting: Boolean = false,
    onConvert: (String) -> Unit = {},
    onClearConversion: () -> Unit = {},
) {
    val context = LocalContext.current
    val feedback = LocalFeedbackController.current
    var note by remember { mutableStateOf<Note?>(null) }
    var showImagePreview by remember { mutableStateOf(false) }
    var aiStyle by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(noteId) {
        note = repository.getNoteById(noteId)
    }

    // 当 AI 转换完成后，自动选中对应风格标签
    LaunchedEffect(aiConvertedContent) {
        if (aiConvertedContent != null && aiStyle == null) {
            // 如果有转换结果但未选风格，保持当前状态
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_share_note)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(SpacingTokens.lg)
                .verticalScroll(rememberScrollState()),
        ) {
            // 笔记预览卡片
            note?.let { noteItem ->
                val displayContent = aiConvertedContent ?: noteItem.content
                val isAiActive = aiConvertedContent != null

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ShapeTokens.mediumShape,
                    colors = CardDefaults.cardColors(
                        containerColor = if (isAiActive)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(modifier = Modifier.padding(SpacingTokens.lg)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = noteItem.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                modifier = Modifier.weight(1f),
                            )
                            if (isAiActive) {
                                Spacer(Modifier.width(SpacingTokens.sm))
                                AssistChip(
                                    onClick = {},
                                    label = { Text("AI 已转换", style = MaterialTheme.typography.labelSmall) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            contentDescription = "AI 已转换",
                                            modifier = Modifier.size(14.dp),
                                        )
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(SpacingTokens.sm))
                        Text(
                            text = displayContent.take(500),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 10,
                        )
                        if (displayContent.length > 500) {
                            Text(
                                text = "...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // AI 转换后的操作按钮
                if (isAiActive) {
                    Spacer(Modifier.height(SpacingTokens.sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("note", aiConvertedContent)
                                clipboard.setPrimaryClip(clip)
                                feedback.showFeedback("已复制转换内容")
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(SpacingTokens.xs))
                            Text("复制", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = { onClearConversion() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "恢复原文", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(SpacingTokens.xs))
                            Text("恢复原文", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // AI 风格转换区域
            Text(
                text = "AI 风格转换",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(SpacingTokens.sm))

            Row(
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilterChip(
                    selected = aiStyle == "xiaohongshu",
                    onClick = { aiStyle = if (aiStyle == "xiaohongshu") null else "xiaohongshu" },
                    label = { Text("小红书") },
                    leadingIcon = if (aiStyle == "xiaohongshu") {
                        { Icon(Icons.Default.Check, contentDescription = "已选中", modifier = Modifier.size(16.dp)) }
                    } else null,
                )
                FilterChip(
                    selected = aiStyle == "wechat",
                    onClick = { aiStyle = if (aiStyle == "wechat") null else "wechat" },
                    label = { Text("公众号") },
                    leadingIcon = if (aiStyle == "wechat") {
                        { Icon(Icons.Default.Check, contentDescription = "已选中", modifier = Modifier.size(16.dp)) }
                    } else null,
                )
                FilterChip(
                    selected = aiStyle == "moments",
                    onClick = { aiStyle = if (aiStyle == "moments") null else "moments" },
                    label = { Text("朋友圈") },
                    leadingIcon = if (aiStyle == "moments") {
                        { Icon(Icons.Default.Check, contentDescription = "已选中", modifier = Modifier.size(16.dp)) }
                    } else null,
                )
            }

            Spacer(Modifier.height(SpacingTokens.sm))

            Button(
                onClick = {
                    aiStyle?.let { style -> onConvert(style) }
                },
                enabled = aiStyle != null && !isConverting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isConverting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Text("AI 转换中...")
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "AI 转换", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Text("AI 转换")
                }
            }

            Spacer(Modifier.height(20.dp))

            // 分享平台网格
            Text(
                text = "分享到平台",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(SpacingTokens.md))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                sharePlatforms.take(4).forEach { platform ->
                    SharePlatformItem(
                        platform = platform,
                        onClick = {
                            note?.let { noteItem ->
                                val contentToShare = aiConvertedContent ?: noteItem.content
                                shareToPlatform(context, noteItem, platform, contentToShare) { msg -> feedback.showFeedback(msg) }
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(SpacingTokens.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                sharePlatforms.drop(4).forEach { platform ->
                    SharePlatformItem(
                        platform = platform,
                        onClick = {
                            note?.let { noteItem ->
                                val contentToShare = aiConvertedContent ?: noteItem.content
                                shareToPlatform(context, noteItem, platform, contentToShare) { msg -> feedback.showFeedback(msg) }
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // 分享格式选项
            Text(
                text = "分享格式",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(SpacingTokens.md))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
            ) {
                items(shareFormats, key = { it.name }) { format ->
                    ShareFormatItem(
                        format = format,
                        onClick = {
                            note?.let { noteItem ->
                                val contentToShare = aiConvertedContent ?: noteItem.content
                                shareAsFormat(context, noteItem, format, contentToShare) { msg -> feedback.showFeedback(msg) }
                            }
                        },
                    )
                }
            }
        }
    }
}

data class SharePlatform(
    val name: String,
    val icon: ImageVector,
    val color: Color,
    val action: String,
)

data class ShareFormat(
    val name: String,
    val icon: ImageVector,
    val color: Color,
    val format: String,
)

// 以下颜色为第三方平台品牌色（微信/朋友圈绿、小红书红等），按平台官方规范硬编码，不参与主题切换
val sharePlatforms = listOf(
    SharePlatform("微信", Icons.AutoMirrored.Filled.Chat, Color(0xFF07C160), "wechat"),
    SharePlatform("朋友圈", Icons.Default.Public, Color(0xFF07C160), "moments"),
    SharePlatform("小红书", Icons.Default.Image, Color(0xFFFF2442), "xiaohongshu"),
    SharePlatform("复制链接", Icons.Default.Link, AccentBlue, "copy_link"),
    SharePlatform("复制文本", Icons.Default.ContentCopy, Color(0xFF9B59B6), "copy_text"),
    SharePlatform("保存图片", Icons.Default.Image, Color(0xFF3498DB), "save_image"),
    SharePlatform("发送邮件", Icons.Default.Email, Color(0xFF34495E), "email"),
    SharePlatform("更多", Icons.Default.Share, Color(0xFF95A5A6), "more"),
)

val shareFormats = listOf(
    ShareFormat("文本", Icons.Default.TextFields, Color(0xFF4A90D9), "text"),
    ShareFormat("Markdown", Icons.Default.Code, Color(0xFF2ECC71), "markdown"),
    ShareFormat("HTML", Icons.Default.Language, Color(0xFFE74C3C), "html"),
)

@Composable
private fun SharePlatformItem(
    platform: SharePlatform,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_share), onClick = onClick),
    ) {
        Surface(
            color = platform.color.copy(alpha = 0.1f),
            shape = CircleShape,
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    platform.icon,
                    contentDescription = platform.name,
                    tint = platform.color,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.height(SpacingTokens.xs))
        Text(
            platform.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShareFormatItem(
    format: ShareFormat,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = ShapeTokens.smallShape,
        colors = CardDefaults.cardColors(containerColor = format.color.copy(alpha = 0.1f)),
    ) {
        Row(
            modifier = Modifier.padding(SpacingTokens.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                format.icon,
                contentDescription = format.name,
                tint = format.color,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(SpacingTokens.sm))
            Text(
                format.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun shareToPlatform(context: Context, note: Note, platform: SharePlatform, overrideContent: String? = null, onFeedback: (String) -> Unit) {
    val content = overrideContent ?: note.content
    val shareText = buildString {
        appendLine(note.title)
        appendLine()
        appendLine(content.take(1000))
        if (content.length > 1000) {
            appendLine("...")
        }
    }

    when (platform.action) {
        "copy_link" -> {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("note", shareText)
            clipboard.setPrimaryClip(clip)
            onFeedback(context.getString(R.string.msg_copied_to_clipboard))
        }
        "copy_text" -> {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("note", shareText)
            clipboard.setPrimaryClip(clip)
            onFeedback("已复制文本")
        }
        "email" -> {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, note.title)
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            context.startActivity(Intent.createChooser(intent, "分享笔记"))
        }
        else -> {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            context.startActivity(Intent.createChooser(intent, "分享到${platform.name}"))
        }
    }
}

private fun shareAsFormat(context: Context, note: Note, format: ShareFormat, overrideContent: String? = null, onFeedback: (String) -> Unit) {
    val content = overrideContent ?: note.content
    val shareText = when (format.format) {
        "text" -> content
        "markdown" -> buildString {
            appendLine("# ${note.title}")
            appendLine()
            appendLine(content)
        }
        "html" -> buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html>")
            appendLine("<head><title>${note.title}</title></head>")
            appendLine("<body>")
            appendLine("<h1>${note.title}</h1>")
            appendLine("<p>${content.replace("\n", "<br>")}</p>")
            appendLine("</body>")
            appendLine("</html>")
        }
        else -> content
    }

    when (format.format) {
        "text", "markdown" -> {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("note", shareText)
            clipboard.setPrimaryClip(clip)
            onFeedback("已复制${format.name}")
        }
        "html" -> {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_SUBJECT, note.title)
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            context.startActivity(Intent.createChooser(intent, "分享HTML"))
        }
    }
}
