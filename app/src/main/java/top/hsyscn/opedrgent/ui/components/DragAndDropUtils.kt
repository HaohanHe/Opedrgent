package top.hsyscn.opedrgent.ui.components

import android.content.ClipData
import android.net.Uri
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

/**
 * 构建笔记拖拽时导出的文本内容。
 */
private fun buildNoteClipText(note: Note): String = buildString {
    if (note.title.isNotBlank()) {
        appendLine(note.title)
        appendLine()
    }
    append(note.content)
}

/**
 * 将笔记作为跨窗口拖拽源。
 *
 * 长按并移动时，将笔记标题+内容以 text/plain 形式拖出到其他应用。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
fun Modifier.dragNoteSource(note: Note): Modifier = this.then(
    Modifier.dragAndDropSource { _ ->
        DragAndDropTransferData(
            clipData = ClipData.newPlainText("opedrgent_note", buildNoteClipText(note)),
            flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ,
        )
    },
)

/**
 * 接收拖拽进入的纯文本内容。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun Modifier.dropTextTarget(
    onTextDropped: (String) -> Unit,
): Modifier = this.then(
    Modifier.dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            event.toAndroidDragEvent().clipData.description.hasMimeType("text/plain")
        },
        target = remember {
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val text = event.toAndroidDragEvent().clipData.getItemAt(0)?.text?.toString()
                    return if (!text.isNullOrBlank()) {
                        onTextDropped(text)
                        true
                    } else {
                        false
                    }
                }
            }
        },
    ),
)

/**
 * 接收拖拽进入的图片内容。
 *
 * @param onImageDropped 传入图片 Uri，由调用方决定如何保存/插入
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun Modifier.dropImageTarget(
    onImageDropped: (Uri) -> Unit,
): Modifier = this.then(
    Modifier.dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            event.toAndroidDragEvent().clipData.description.hasMimeType("image/*")
        },
        target = remember {
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val uri = event.toAndroidDragEvent().clipData.getItemAt(0)?.uri ?: return false
                    onImageDropped(uri)
                    return true
                }
            }
        },
    ),
)

/**
 * 同时接收文本和图片的拖拽目标。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun Modifier.dropContentTarget(
    onTextDropped: (String) -> Unit,
    onImageDropped: (Uri) -> Unit,
): Modifier = this.then(
    Modifier.dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            val description = event.toAndroidDragEvent().clipData.description
            description.hasMimeType("text/plain") || description.hasMimeType("image/*")
        },
        target = remember {
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val clipData = event.toAndroidDragEvent().clipData
                    return when {
                        clipData.description.hasMimeType("image/*") -> {
                            val uri = clipData.getItemAt(0)?.uri
                            if (uri != null) {
                                onImageDropped(uri)
                                true
                            } else false
                        }
                        clipData.description.hasMimeType("text/plain") -> {
                            val text = clipData.getItemAt(0)?.text?.toString()
                            if (!text.isNullOrBlank()) {
                                onTextDropped(text)
                                true
                            } else false
                        }
                        else -> false
                    }
                }
            }
        },
    ),
)

/**
 * 笔记卡片拖拽手柄。
 *
 * 放在卡片右侧，长按并移动可触发跨应用拖拽。
 */
@Composable
fun NoteDragHandle(
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Default.DragHandle,
        contentDescription = stringResource(R.string.cd_drag_handle),
        tint = MaterialTheme.colorScheme.outline,
        modifier = modifier.padding(start = SpacingTokens.sm),
    )
}
