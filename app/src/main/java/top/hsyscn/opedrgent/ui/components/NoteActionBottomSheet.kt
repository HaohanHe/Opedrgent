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
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.note.ExportFormat
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.exportNoteToFile
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import androidx.compose.ui.res.stringResource

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
                    headlineContent = { Text(stringResource(R.string.note_action_choose_export_format), style = MaterialTheme.typography.titleSmall) },
                    leadingContent = {
                        IconButton(onClick = { showExportSubmenu = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                        }
                    },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.note_action_export_txt)) },
                    leadingContent = {
                        Icon(Icons.Default.Description, stringResource(R.string.note_action_cd_export_txt), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
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
                                context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.note_action_share_note_title)))
                            } catch (e: Exception) {
                                feedback.showFeedback(context.getString(R.string.note_action_export_failed, e.message ?: ""))
                            }
                            onDismiss()
                        }
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.note_action_export_markdown)) },
                    leadingContent = {
                        Icon(Icons.Default.Description, stringResource(R.string.note_action_cd_export_markdown), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
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
                                context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.note_action_share_note_title)))
                            } catch (e: Exception) {
                                feedback.showFeedback(context.getString(R.string.note_action_export_failed, e.message ?: ""))
                            }
                            onDismiss()
                        }
                    },
                )
            } else {
                // 主菜单
                ListItem(
                    headlineContent = { Text(stringResource(R.string.note_action_title), style = MaterialTheme.typography.titleSmall) },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_edit)) },
                    leadingContent = {
                        Icon(Icons.Default.Edit, stringResource(R.string.cd_edit), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onEdit() },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_share)) },
                    leadingContent = {
                        Icon(Icons.Default.Share, stringResource(R.string.cd_share), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onShare() },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.note_action_append)) },
                    leadingContent = {
                        Icon(Icons.Default.AddCircleOutline, stringResource(R.string.note_action_cd_append), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onAppend() },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_export)) },
                    leadingContent = {
                        Icon(Icons.Default.FileDownload, stringResource(R.string.note_action_cd_export), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Icon(Icons.Default.ChevronRight, stringResource(R.string.note_action_cd_enter_export_submenu), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    modifier = Modifier.clickable { showExportSubmenu = true },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.note_action_copy_summary)) },
                    leadingContent = {
                        Icon(Icons.Default.ContentCopy, stringResource(R.string.note_action_cd_copy_summary), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable {
                        val summary = note.summary.ifBlank { note.content.take(200) }
                        clipboardManager.setText(AnnotatedString(summary))
                        feedback.showFeedback(context.getString(R.string.note_action_summary_copied))
                        onDismiss()
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.note_action_copy_full_text)) },
                    leadingContent = {
                        Icon(Icons.Default.CopyAll, stringResource(R.string.note_action_cd_copy_full_text), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable {
                        clipboardManager.setText(AnnotatedString(note.content))
                        feedback.showFeedback(context.getString(R.string.note_action_full_text_copied))
                        onDismiss()
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.note_action_correct)) },
                    leadingContent = {
                        Icon(Icons.Default.Spellcheck, stringResource(R.string.note_action_cd_correct), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.tertiary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onCorrect() },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.note_action_sprout)) },
                    leadingContent = {
                        Icon(Icons.Default.AutoAwesome, stringResource(R.string.note_action_cd_sprout), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onSprout() },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.note_action_add_to_kb)) },
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Filled.LibraryBooks, stringResource(R.string.note_action_cd_add_to_kb), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.secondary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onAddToKnowledgeBase() },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.note_action_add_tag)) },
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Filled.Label, stringResource(R.string.note_action_cd_add_tag), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.tertiary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onAddTag() },
                )
                ListItem(
                    headlineContent = { Text(stringResource(if (note.isPinned) R.string.note_action_unpin else R.string.note_action_pin)) },
                    leadingContent = {
                        Icon(Icons.Default.PushPin, stringResource(if (note.isPinned) R.string.note_action_cd_unpin else R.string.note_action_cd_pin), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onTogglePin() },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.note_action_discuss_in_chat)) },
                    leadingContent = {
                        Icon(Icons.Default.ChatBubbleOutline, stringResource(R.string.note_action_cd_discuss_in_chat), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onDismiss(); onSendToChat() },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                    leadingContent = {
                        Icon(Icons.Default.Delete, stringResource(R.string.cd_delete), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable { onDismiss(); onDelete() },
                )
                Spacer(Modifier.height(SpacingTokens.lg))
            }
        }
    }
}
