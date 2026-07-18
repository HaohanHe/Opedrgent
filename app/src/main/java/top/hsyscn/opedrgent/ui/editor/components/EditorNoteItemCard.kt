package top.hsyscn.opedrgent.ui.editor.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.theme.AccentPurple
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.customColors
import top.hsyscn.opedrgent.ui.editor.utils.EditorUtils

/**
 * 编辑器内单条笔记卡片组件
 */
@Composable
fun EditorNoteItemCard(
    noteId: String,
    content: String,
    createdAtMs: Long,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var showConfirm by remember { mutableStateOf(false) }

    Surface(
        shape = ShapeTokens.smallShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(SpacingTokens.lg),
                ) {}
                Spacer(Modifier.width(SpacingTokens.sm))
                Text(
                    EditorUtils.formatTimeAgo(context, createdAtMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit, modifier = Modifier.size(SpacingTokens.xl)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.cd_edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(SpacingTokens.sm)
                    )
                }
                IconButton(onClick = { showConfirm = true }, modifier = Modifier.size(SpacingTokens.xl)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.cd_delete),
                        tint = MaterialTheme.customColors.dangerRed,
                        modifier = Modifier.size(SpacingTokens.sm)
                    )
                }
            }
            Spacer(Modifier.height(SpacingTokens.sm))
            Text(
                content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (showConfirm) {
                Surface(
                    shape = ShapeTokens.smallShape,
                    color = MaterialTheme.customColors.errorBackground,
                    border = BorderStroke(1.dp, MaterialTheme.customColors.errorBorder),
                    modifier = Modifier
                        .padding(top = SpacingTokens.sm)
                        .fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm)
                    ) {
                        Text(
                            stringResource(R.string.note_editor_delete_this_note_confirm),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.customColors.deleteConfirmRed,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = ShapeTokens.extraSmallShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                            onClick = { showConfirm = false }
                        ) {
                            Text(
                                stringResource(R.string.action_cancel),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = SpacingTokens.xs, vertical = SpacingTokens.xxs)
                            )
                        }
                        Surface(
                            shape = ShapeTokens.extraSmallShape,
                            color = MaterialTheme.customColors.deleteConfirmRed,
                            onClick = { onDelete(); showConfirm = false }
                        ) {
                            Text(
                                stringResource(R.string.action_delete),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.padding(horizontal = SpacingTokens.xs, vertical = SpacingTokens.xxs)
                            )
                        }
                    }
                }
            }
        }
    }
}