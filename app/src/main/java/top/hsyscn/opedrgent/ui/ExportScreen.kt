package top.hsyscn.opedrgent.ui

import top.hsyscn.opedrgent.ui.theme.InterviewPurple
import top.hsyscn.opedrgent.ui.theme.InterviewDarkBg
import top.hsyscn.opedrgent.ui.theme.WarningColor
import top.hsyscn.opedrgent.ui.theme.SuccessGreen
import top.hsyscn.opedrgent.ui.theme.AccentOrange
import top.hsyscn.opedrgent.ui.theme.customColors
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Html
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import top.hsyscn.opedrgent.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_export), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        containerColor = themeBgGray(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(SpacingTokens.lg)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "会话导出",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = SpacingTokens.sm),
            )

            ExportOptionCard(
                icon = Icons.Default.Description,
                title = "导出为 Markdown",
                description = "完整的会话记录，包含所有消息和工具调用结果",
                onClick = {
                    scope.launch {
                        val file = vm.exportMarkdown()
                        if (file != null) {
                            shareFile(context, vm.getPackageNameForShare(context), file, "text/markdown")
                        } else {
                            snackbar.showSnackbar(context.getString(R.string.msg_no_sessions_to_export))
                        }
                    }
                },
            )

            ExportOptionCard(
                icon = Icons.AutoMirrored.Filled.TextSnippet,
                title = "导出纯文本",
                description = "仅导出用户和 AI 的对话内容",
                onClick = {
                    scope.launch {
                        val file = vm.exportChatMarkdown()
                        if (file != null) {
                            shareFile(context, vm.getPackageNameForShare(context), file, "text/plain")
                        } else {
                            snackbar.showSnackbar(context.getString(R.string.msg_no_sessions_to_export))
                        }
                    }
                },
            )

            ExportOptionCard(
                icon = Icons.Default.FileDownload,
                title = "导出上下文包",
                description = "包含会话、记忆、笔记、来源的完整上下文",
                onClick = {
                    scope.launch {
                        val file = vm.exportContextZip()
                        if (file != null) {
                            shareFile(context, vm.getPackageNameForShare(context), file, "application/zip")
                        } else {
                            snackbar.showSnackbar("没有可导出的上下文")
                        }
                    }
                },
            )

            Spacer(Modifier.height(SpacingTokens.xl))

            HorizontalDivider()

            Spacer(Modifier.height(SpacingTokens.xl))

            Text(
                text = "复制到剪贴板",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = SpacingTokens.sm),
            )

            ExportOptionCard(
                icon = Icons.Default.ContentCopy,
                title = "复制 Markdown",
                description = "将会话内容复制为 Markdown 格式",
                onClick = {
                    scope.launch {
                        val file = vm.exportMarkdown()
                        if (file != null) {
                            val text = file.readText()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("export", text))
                            snackbar.showSnackbar(context.getString(R.string.msg_copied_to_clipboard))
                        } else {
                            snackbar.showSnackbar(context.getString(R.string.msg_nothing_to_copy))
                        }
                    }
                },
            )

            ExportOptionCard(
                icon = Icons.Default.ContentCopy,
                title = "复制纯文本",
                description = "将对话内容复制为纯文本格式",
                onClick = {
                    scope.launch {
                        val file = vm.exportChatMarkdown()
                        if (file != null) {
                            val text = file.readText()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("export", text))
                            snackbar.showSnackbar(context.getString(R.string.msg_copied_to_clipboard))
                        } else {
                            snackbar.showSnackbar(context.getString(R.string.msg_nothing_to_copy))
                        }
                    }
                },
            )

            Spacer(Modifier.height(SpacingTokens.xl))

            HorizontalDivider()

            Spacer(Modifier.height(SpacingTokens.xl))

            Text(
                text = "分享",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = SpacingTokens.sm),
            )

            ExportOptionCard(
                icon = Icons.Default.IosShare,
                title = "分享 Markdown 文件",
                description = "通过微信、邮件等分享 .md 文件",
                onClick = {
                    scope.launch {
                        val file = vm.exportMarkdown()
                        if (file != null) {
                            shareFile(context, vm.getPackageNameForShare(context), file, "text/markdown")
                        } else {
                            snackbar.showSnackbar(context.getString(R.string.msg_no_files_to_share))
                        }
                    }
                },
            )

            ExportOptionCard(
                icon = Icons.Default.Html,
                title = "生成 HTML",
                description = "将会话转为 HTML 文件，可在浏览器中查看",
                onClick = {
                    scope.launch {
                        val file = vm.exportMarkdown()
                        if (file != null) {
                            val md = file.readText()
                            val html = markdownToHtml(md, "Opedrgent 会话")
                            val htmlFile = File(file.parent, file.nameWithoutExtension + ".html")
                            htmlFile.writeText(html, Charsets.UTF_8)
                            shareFile(context, vm.getPackageNameForShare(context), htmlFile, "text/html")
                        } else {
                            snackbar.showSnackbar(context.getString(R.string.msg_no_files_to_share))
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ExportOptionCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ShapeTokens.mediumShape,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpacingTokens.xs),
    ) {
        Row(
            modifier = Modifier.padding(SpacingTokens.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = ShapeTokens.smallShape,
                color = AccentBlue.copy(alpha = 0.1f),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(20.dp).padding(SpacingTokens.md),
                )
            }

            Spacer(Modifier.width(SpacingTokens.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge,
                    color = themeTextDark(),
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = themeTextGrey(),
                )
            }
        }
    }
}

private fun markdownToHtml(markdown: String, title: String): String {
    return buildString {
        append("<!DOCTYPE html>")
        append("<html><head>")
        append("<meta charset=\"UTF-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        append("<title>${escapeHtml(title)}</title>")
        append("<style>")
        append("body{font-family:system-ui,-apple-system,sans-serif;max-width:800px;margin:0 auto;padding:20px;line-height:1.7;color:#333;}")
        append("h1{color:#1a1a1a;border-bottom:2px solid #2563eb;padding-bottom:8px;}")
        append("h2{color:#374151;margin-top:24px;}")
        append("h3{color:#6b7280;font-size:15px;font-weight:600;margin-top:16px;}")
        append("h4{color:#9ca3af;font-size:14px;font-weight:600;margin-top:12px;}")
        append("pre{background:#f3f4f6;padding:14px;border-radius:8px;overflow-x:auto;font-size:13px;line-height:1.5;}")
        append("pre code{background:none;padding:0;}")
        append("code{background:#f3f4f6;padding:2px 6px;border-radius:4px;font-size:13px;font-family:ui-monospace,monospace;}")
        append("blockquote{border-left:4px solid #2563eb;padding-left:16px;color:#6b7280;margin:16px 0;background:#f8fafc;padding:12px 16px;border-radius:0 8px 8px 0;}")
        append("a{color:#2563eb;text-decoration:none;}")
        append("a:hover{text-decoration:underline;}")
        append("hr{border:none;border-top:1px solid #e5e7eb;margin:24px 0;}")
        append("table{border-collapse:collapse;width:100%;margin:16px 0;}")
        append("th,td{border:1px solid #e5e7eb;padding:8px 12px;text-align:left;}")
        append("th{background:#f9fafb;font-weight:600;}")
        append("ul,ol{padding-left:24px;margin:8px 0;}")
        append("li{margin:4px 0;}")
        append("strong{font-weight:600;color:#1a1a1a;}")
        append("em{font-style:italic;}")
        append("del{text-decoration:line-through;color:#9ca3af;}")
        append(".meta{color:#9ca3af;font-size:12px;margin-bottom:24px;}")
        append("</style>")
        append("</head><body>")

        val lines = markdown.lines()
        var inCodeBlock = false
        var codeLang = ""
        val codeBuffer = StringBuilder()
        var inList = false
        var listType = "" // "ul" or "ol"

        for (line in lines) {
            when {
                line.trimStart().startsWith("```") -> {
                    if (inCodeBlock) {
                        append("<pre><code>${escapeHtml(codeBuffer.toString().trimEnd())}</code></pre>\n")
                        codeBuffer.clear()
                        inCodeBlock = false
                        codeLang = ""
                    } else {
                        if (inList) { append("</$listType>\n"); inList = false }
                        inCodeBlock = true
                        codeLang = line.trimStart().removePrefix("```").trim()
                    }
                }
                inCodeBlock -> {
                    if (codeBuffer.isNotEmpty()) codeBuffer.append("\n")
                    codeBuffer.append(line)
                }
                line.matches(Regex("^#{1,4}\\s+.*")) -> {
                    if (inList) { append("</$listType>\n"); inList = false }
                    val level = line.takeWhile { it == '#' }.length
                    val text = line.removePrefix("#".repeat(level)).trim()
                    append("<h$level>${inlineFormat(text)}</h$level>\n")
                }
                line.startsWith("> ") -> {
                    if (inList) { append("</$listType>\n"); inList = false }
                    append("<blockquote>${inlineFormat(line.removePrefix("> "))}</blockquote>\n")
                }
                line.matches(Regex("^[-*+]\\s+.*")) -> {
                    if (!inList || listType != "ul") {
                        if (inList) append("</$listType>\n")
                        append("<ul>\n"); inList = true; listType = "ul"
                    }
                    append("<li>${inlineFormat(line.replaceFirst(Regex("^[-*+]\\s+"), ""))}</li>\n")
                }
                line.matches(Regex("^\\d+\\.\\s+.*")) -> {
                    if (!inList || listType != "ol") {
                        if (inList) append("</$listType>\n")
                        append("<ol>\n"); inList = true; listType = "ol"
                    }
                    append("<li>${inlineFormat(line.replaceFirst(Regex("^\\d+\\.\\s+"), ""))}</li>\n")
                }
                line.matches(Regex("^---+\\s*$")) -> {
                    if (inList) { append("</$listType>\n"); inList = false }
                    append("<hr>\n")
                }
                line.isBlank() -> {
                    if (inList) { append("</$listType>\n"); inList = false }
                }
                else -> {
                    if (inList) { append("</$listType>\n"); inList = false }
                    append("<p>${inlineFormat(line)}</p>\n")
                }
            }
        }

        if (inCodeBlock && codeBuffer.isNotEmpty()) {
            append("<pre><code>${escapeHtml(codeBuffer.toString().trimEnd())}</code></pre>\n")
        }
        if (inList) append("</$listType>\n")

        append("</body></html>")
    }
}

/** Markdown 行内格式转换：粗体、斜体、行内代码、链接、删除线 */
private fun StringBuilder.inlineFormat(text: String): String {
    var result = escapeHtml(text)
    // 行内代码 `code`
    result = result.replace(Regex("`([^`]+)`"), "<code>$1</code>")
    // 粗体 **text** 或 __text__
    result = result.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
    result = result.replace(Regex("__(.+?)__"), "<strong>$1</strong>")
    // 斜体 *text* 或 _text_（不匹配已处理的粗体）
    result = result.replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), "<em>$1</em>")
    result = result.replace(Regex("(?<!_)_(?!_)(.+?)(?<!_)_(?!_)"), "<em>$1</em>")
    // 删除线 ~~text~~
    result = result.replace(Regex("~~(.+?)~~"), "<del>$1</del>")
    // 链接 [text](url)
    result = result.replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")
    return result
}

private fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
