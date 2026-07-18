package top.hsyscn.opedrgent.ui.editor.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.theme.AccentPurple
import top.hsyscn.opedrgent.ui.theme.DisabledColor
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.themeBorderLight
import top.hsyscn.opedrgent.ui.theme.themeSurfaceElevated
import top.hsyscn.opedrgent.ui.theme.themeSurfaceLight
import top.hsyscn.opedrgent.ui.editor.components.EditorNoteItemCard

/** 单条编辑器内笔记 */
data class EditorNote(
    val id: String,
    val content: String,
    val createdAtMs: Long = System.currentTimeMillis(),
)

/**
 * 追加笔记标签页组件
 * 提供笔记的追加编辑功能
 */
@Composable
fun EditorAdditionalNotesTab() {
    var notes by remember { mutableStateOf(listOf<EditorNote>()) }
    var isEditing by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (notes.isEmpty() && !isEditing) {
            // 可操作的空状态
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SpacingTokens.xxl),
            ) {
                Text(
                    stringResource(R.string.note_editor_no_additional_notes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.note_editor_additional_notes_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = SpacingTokens.xs),
                )
                Spacer(Modifier.height(SpacingTokens.lg))
                Surface(
                    shape = ShapeTokens.mediumShape,
                    color = themeSurfaceLight(),
                    onClick = { isEditing = true },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.note_editor_cd_add_note),
                            tint = AccentPurple,
                            modifier = Modifier.size(SpacingTokens.md)
                        )
                        Spacer(Modifier.width(SpacingTokens.sm))
                        Text(
                            stringResource(R.string.note_editor_add_note),
                            color = AccentPurple,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        } else {
            // 笔记列表
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                itemsIndexed(notes) { _, note ->
                    EditorNoteItemCard(
                        noteId = note.id,
                        content = note.content,
                        createdAtMs = note.createdAtMs,
                        onEdit = {
                            editingId = note.id
                            inputText = note.content
                            isEditing = true
                        },
                        onDelete = { notes = notes.filter { it.id != note.id } },
                    )
                }
                item { Spacer(Modifier.height(SpacingTokens.xxl)) }
            }
        }

        // 输入区域
        if (isEditing) {
            HorizontalDivider()
            Surface(
                shape = ShapeTokens.mediumShape,
                color = themeSurfaceElevated(),
                modifier = Modifier
                    .padding(SpacingTokens.md)
                    .fillMaxWidth(),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(if (editingId != null) R.string.note_editor_edit_note else R.string.note_editor_new_note),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                isEditing = false
                                inputText = ""
                                editingId = null
                            },
                            modifier = Modifier.size(SpacingTokens.xl)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.cd_close),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(SpacingTokens.lg)
                            )
                        }
                    }
                    Spacer(Modifier.height(SpacingTokens.sm))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                stringResource(R.string.note_editor_note_placeholder),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp, max = 120.dp),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPurple,
                            unfocusedBorderColor = themeBorderLight(),
                        ),
                        shape = ShapeTokens.smallShape,
                    )
                    Spacer(Modifier.height(SpacingTokens.sm))
                    Row(horizontalArrangement = Arrangement.End) {
                        Surface(
                            shape = ShapeTokens.smallShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                            onClick = {
                                isEditing = false
                                inputText = ""
                                editingId = null
                            }
                        ) {
                            Text(
                                stringResource(R.string.action_cancel),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm)
                            )
                        }
                        Spacer(Modifier.width(SpacingTokens.sm))
                        Surface(
                            shape = ShapeTokens.smallShape,
                            color = if (inputText.isNotBlank()) AccentPurple else DisabledColor,
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    if (editingId != null) {
                                        notes = notes.map {
                                            if (it.id == editingId) it.copy(content = inputText.trim()) else it
                                        }
                                    } else {
                                        notes = notes + EditorNote(
                                            System.nanoTime().toString(),
                                            inputText.trim()
                                        )
                                    }
                                    isEditing = false
                                    inputText = ""
                                    editingId = null
                                }
                            },
                        ) {
                            Text(
                                text = stringResource(if (editingId != null) R.string.action_save else R.string.action_add),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.sm),
                            )
                        }
                    }
                }
            }
        } else if (notes.isNotEmpty()) {
            // 浮动添加按钮
            Surface(
                shape = ShapeTokens.mediumShape,
                border = BorderStroke(1.dp, themeBorderLight()),
                onClick = { isEditing = true },
                modifier = Modifier
                    .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm)
                    .fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.note_editor_cd_add_note),
                        tint = AccentPurple,
                        modifier = Modifier.size(SpacingTokens.lg)
                    )
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Text(
                        stringResource(R.string.note_editor_append_note_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}