package top.hsyscn.opedrgent.ui

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Html
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.TextSnippet
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.BgGray
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                title = { Text("导出", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        containerColor = BgGray,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "会话导出",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 8.dp),
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
                            snackbar.showSnackbar("没有可导出的会话")
                        }
                    }
                },
            )

            ExportOptionCard(
                icon = Icons.Default.TextSnippet,
                title = "导出纯文本",
                description = "仅导出用户和 AI 的对话内容",
                onClick = {
                    scope.launch {
                        val file = vm.exportChatMarkdown()
                        if (file != null) {
                            shareFile(context, vm.getPackageNameForShare(context), file, "text/plain")
                        } else {
                            snackbar.showSnackbar("没有可导出的会话")
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

            Spacer(Modifier.height(20.dp))

            HorizontalDivider()

            Spacer(Modifier.height(20.dp))

            Text(
                text = "复制到剪贴板",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 8.dp),
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
                            snackbar.showSnackbar("已复制到剪贴板")
                        } else {
                            snackbar.showSnackbar("没有可复制的内容")
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
                            snackbar.showSnackbar("已复制到剪贴板")
                        } else {
                            snackbar.showSnackbar("没有可复制的内容")
                        }
                    }
                },
            )

            Spacer(Modifier.height(20.dp))

            HorizontalDivider()

            Spacer(Modifier.height(20.dp))

            Text(
                text = "分享",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 8.dp),
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
                            snackbar.showSnackbar("没有可分享的文件")
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
                            snackbar.showSnackbar("没有可分享的文件")
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
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = AccentBlue.copy(alpha = 0.1f),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(20.dp).padding(10.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = TextDark,
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = TextGrey,
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
        append("<title>${title}</title>")
        append("<style>")
        append("body{font-family:system-ui,-apple-system,sans-serif;max-width:800px;margin:0 auto;padding:20px;line-height:1.6;color:#333;}")
        append("h1{color:#1a1a1a;border-bottom:2px solid #2563eb;padding-bottom:8px;}")
        append("h2{color:#374151;margin-top:24px;}")
        append("h3{color:#6b7280;font-size:14px;font-weight:600;margin-top:16px;}")
        append("pre{background:#f3f4f6;padding:12px;border-radius:8px;overflow-x:auto;font-size:13px;}")
        append("code{background:#f3f4f6;padding:2px 6px;border-radius:4px;font-size:13px;}")
        append("blockquote{border-left:4px solid #2563eb;padding-left:16px;color:#6b7280;margin:16px 0;}")
        append("a{color:#2563eb;text-decoration:none;}")
        append("a:hover{text-decoration:underline;}")
        append("hr{border:none;border-top:1px solid #e5e7eb;margin:24px 0;}")
        append("table{border-collapse:collapse;width:100%;margin:16px 0;}")
        append("th,td{border:1px solid #e5e7eb;padding:8px 12px;text-align:left;}")
        append("th{background:#f9fafb;font-weight:600;}")
        append(".meta{color:#9ca3af;font-size:12px;margin-bottom:24px;}")
        append("</style>")
        append("</head><body>")

        // Simple markdown to HTML conversion
        val lines = markdown.lines()
        var inCodeBlock = false
        var codeBuffer = StringBuilder()

        for (line in lines) {
            when {
                line.startsWith("```") -> {
                    if (inCodeBlock) {
                        append("<pre><code>${escapeHtml(codeBuffer.toString())}</code></pre>")
                        codeBuffer = StringBuilder()
                        inCodeBlock = false
                    } else {
                        inCodeBlock = true
                    }
                }
                inCodeBlock -> {
                    if (codeBuffer.isNotEmpty()) codeBuffer.append("\n")
                    codeBuffer.append(line)
                }
                line.startsWith("# ") -> append("<h1>${escapeHtml(line.removePrefix("# "))}</h1>\n")
                line.startsWith("## ") -> append("<h2>${escapeHtml(line.removePrefix("## "))}</h2>\n")
                line.startsWith("### ") -> append("<h3>${escapeHtml(line.removePrefix("### "))}</h3>\n")
                line.startsWith("> ") -> append("<blockquote>${escapeHtml(line.removePrefix("> "))}</blockquote>\n")
                line.startsWith("- ") -> append("<li>${escapeHtml(line.removePrefix("- "))}</li>\n")
                line.startsWith("---") -> append("<hr>\n")
                line.isBlank() -> append("<br>\n")
                else -> append("<p>${escapeHtml(line)}</p>\n")
            }
        }

        if (inCodeBlock && codeBuffer.isNotEmpty()) {
            append("<pre><code>${escapeHtml(codeBuffer.toString())}</code></pre>")
        }

        append("</body></html>")
    }
}

private fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
