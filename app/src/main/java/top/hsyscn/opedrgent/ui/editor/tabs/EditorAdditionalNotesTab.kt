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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.hsyscn.opedrgent.ui.theme.AccentPurple
import top.hsyscn.opedrgent.ui.theme.DisabledColor
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
                    .padding(vertical = 40.dp),
            ) {
                Text(
                    "暂无追加笔记",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
                Text(
                    "在此处添加对笔记内容的补充和批注",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = themeSurfaceLight(),
                    onClick = { isEditing = true },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            null,
                            tint = AccentPurple,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "添加笔记",
                            color = AccentPurple,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        } else {
            // 笔记列表
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                item { Spacer(Modifier.height(80.dp)) }
            }
        }

        // 输入区域
        if (isEditing) {
            HorizontalDivider()
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = themeSurfaceElevated(),
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth(),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (editingId != null) "编辑笔记" else "新建笔记",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                isEditing = false
                                inputText = ""
                                editingId = null
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                "关闭",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                "记录你的想法...",
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
                        shape = RoundedCornerShape(8.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.End) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            onClick = {
                                isEditing = false
                                inputText = ""
                                editingId = null
                            }
                        ) {
                            Text(
                                "取消",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
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
                                text = if (editingId != null) "保存" else "添加",
                                color = Color.White,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        } else if (notes.isNotEmpty()) {
            // 浮动添加按钮
            Surface(
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, themeBorderLight()),
                onClick = { isEditing = true },
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        null,
                        tint = AccentPurple,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "追加笔记...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}