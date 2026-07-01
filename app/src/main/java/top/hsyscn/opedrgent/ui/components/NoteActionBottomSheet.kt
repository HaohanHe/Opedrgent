package top.hsyscn.opedrgent.ui.components

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.note.ExportFormat
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.exportNoteToFile
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteActionBottomSheet(
    note: Note,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onAppend: () -> Unit,
    onCorrect: () -> Unit,
    onSprout: () -> Unit,
    onAddToKnowledgeBase: () -> Unit,
    onAddTag: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onSendToChat: () -> Unit,
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val feedback = LocalFeedbackController.current
    var showExportSubmenu by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(bottom = SpacingTokens.xl)) {
            if (showExportSubmenu) {
                // 导出子菜单
                ListItem(
                    headlineContent = { Text("选择导出格式", style = MaterialTheme.typography.titleSmall) },
                    leadingContent = {
                        IconButton(onClick = { showExportSubmenu = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("导出为 TXT") },
                    leadingContent = {
                        Icon(Icons.Default.Description, "导出为 TXT", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable {
                        scope.launch {
                            try {
                                val file = exportNoteToFile(note, ExportFormat.TXT, context)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    ))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "分享笔记"))
                            } catch (e: Exception) {
                                feedback.showFeedback("导出失败: ${e.message}")
                            }
                            onDismiss()
                        }
                    },
                )
                ListItem(
                    headlineContent = { Text("导出为 Markdown") },
                    leadingContent = {
                        Icon(Icons.Default.Description, "导出为 Markdown", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable {
                        scope.launch {
                            try {
                                val file = exportNoteToFile(note, ExportFormat.MARKDOWN, context)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/markdown"
                                    putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    ))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "分享笔记"))
                            } catch (e: Exception) {
                                feedback.showFeedback("导出失败: ${e.message}")
                            }
                            onDismiss()
                        }
                    },
                )
            } else {
                // 主菜单
                ListItem(
                    headlineContent = { Text("笔记操作", style = MaterialTheme.typography.titleSmall) },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("编辑") },
                    leadingContent = {
                        Icon(Icons.Default.Edit, "编辑", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onEdit() },
                )
                ListItem(
                    headlineContent = { Text("分享") },
                    leadingContent = {
                        Icon(Icons.Default.Share, "分享", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onShare() },
                )
                ListItem(
                    headlineContent = { Text("追加笔记") },
                    leadingContent = {
                        Icon(Icons.Default.AddCircleOutline, "追加笔记", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onAppend() },
                )
                ListItem(
                    headlineContent = { Text("导出") },
                    leadingContent = {
                        Icon(Icons.Default.FileDownload, "导出", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Icon(Icons.Default.ChevronRight, "进入导出子菜单", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    modifier = Modifier.clickable { showExportSubmenu = true },
                )
                ListItem(
                    headlineContent = { Text("复制总结") },
                    leadingContent = {
                        Icon(Icons.Default.ContentCopy, "复制总结", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable {
                        val summary = note.summary.ifBlank { note.content.take(200) }
                        clipboardManager.setText(AnnotatedString(summary))
                        feedback.showFeedback("已复制总结到剪贴板")
                        onDismiss()
                    },
                )
                ListItem(
                    headlineContent = { Text("复制文字记录") },
                    leadingContent = {
                        Icon(Icons.Default.CopyAll, "复制全文", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable {
                        clipboardManager.setText(AnnotatedString(note.content))
                        feedback.showFeedback("已复制全文到剪贴板")
                        onDismiss()
                    },
                )
                ListItem(
                    headlineContent = { Text("纠错") },
                    leadingContent = {
                        Icon(Icons.Default.Spellcheck, "纠错", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.tertiary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onCorrect() },
                )
                ListItem(
                    headlineContent = { Text("笔记发芽") },
                    leadingContent = {
                        Icon(Icons.Default.AutoAwesome, "笔记发芽", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onSprout() },
                )
                ListItem(
                    headlineContent = { Text("添加到知识库") },
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Filled.LibraryBooks, "添加到知识库", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.secondary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onAddToKnowledgeBase() },
                )
                ListItem(
                    headlineContent = { Text("添加标签") },
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Filled.Label, "添加标签", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.tertiary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onAddTag() },
                )
                ListItem(
                    headlineContent = { Text(if (note.isPinned) "取消置顶" else "置顶") },
                    leadingContent = {
                        Icon(Icons.Default.PushPin, if (note.isPinned) "取消置顶" else "置顶", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onTogglePin() },
                )
                ListItem(
                    headlineContent = { Text("在对话中讨论") },
                    leadingContent = {
                        Icon(Icons.Default.ChatBubbleOutline, "在对话中讨论", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onSendToChat() },
                )
                ListItem(
                    headlineContent = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    leadingContent = {
                        Icon(Icons.Default.Delete, "删除", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable { onDismiss(); onDelete() },
                )
                Spacer(Modifier.height(SpacingTokens.lg))
            }
        }
    }
}
