package top.hsyscn.opedrgent.ui.editor.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

/**
 * Markdown 格式工具栏组件
 * 提供常用的 Markdown 格式化操作
 */
@Composable
fun MarkdownFormatToolbar(
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onCode: () -> Unit,
    onHeading1: () -> Unit,
    onHeading2: () -> Unit,
    onHeading3: () -> Unit,
    onBulletList: () -> Unit,
    onNumberedList: () -> Unit,
    onQuote: () -> Unit,
    onLink: () -> Unit,
    onImage: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 2.dp,
    ) {
        Column {
            // 第一行：基础格式
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
            ) {
                FormatButton(
                    icon = Icons.Default.FormatBold,
                    onClick = onBold,
                    description = "加粗"
                )
                FormatButton(
                    icon = Icons.Default.FormatItalic,
                    onClick = onItalic,
                    description = "斜体"
                )
                FormatButton(
                    icon = Icons.Default.Code,
                    onClick = onCode,
                    description = "代码"
                )
                FormatButton(
                    icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                    onClick = onBulletList,
                    description = "无序列表"
                )
                FormatButton(
                    icon = Icons.Default.FormatListNumbered,
                    onClick = onNumberedList,
                    description = "有序列表"
                )
                FormatButton(
                    icon = Icons.Default.FormatQuote,
                    onClick = onQuote,
                    description = "引用"
                )
                FormatButton(
                    icon = Icons.Default.Link,
                    onClick = onLink,
                    description = "链接"
                )
                FormatButton(
                    icon = Icons.Default.Image,
                    onClick = onImage,
                    description = "图片"
                )
            }

            // 第二行：标题
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
            ) {
                FormatButton(
                    icon = Icons.Default.Title,
                    onClick = onHeading1,
                    description = "标题1",
                    text = "H1"
                )
                FormatButton(
                    icon = Icons.Default.Title,
                    onClick = onHeading2,
                    description = "标题2",
                    text = "H2"
                )
                FormatButton(
                    icon = Icons.Default.Title,
                    onClick = onHeading3,
                    description = "标题3",
                    text = "H3"
                )
            }
        }
    }
}

/**
 * 格式按钮组件
 */
@Composable
fun FormatButton(
    icon: ImageVector,
    onClick: () -> Unit,
    description: String,
    text: String? = null,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
    ) {
        if (text != null) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            Icon(
                icon,
                description,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}