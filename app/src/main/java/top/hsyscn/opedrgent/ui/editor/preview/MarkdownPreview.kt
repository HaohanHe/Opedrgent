package top.hsyscn.opedrgent.ui.editor.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

/**
 * Markdown 预览组件
 * 提供基础的 Markdown 渲染功能
 */
@Composable
fun MarkdownPreview(
    content: String,
    modifier: Modifier = Modifier,
) {
    // 注意：不在此处添加 verticalScroll，由调用方按需添加，避免双重嵌套崩溃
    Column(modifier = modifier) {
        content.split("\n").forEach { line ->
            when {
                line.startsWith("# ") -> Text(
                    text = line.removePrefix("# "),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(vertical = SpacingTokens.sm),
                )

                line.startsWith("## ") -> Text(
                    text = line.removePrefix("## "),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(vertical = SpacingTokens.sm),
                )

                line.startsWith("### ") -> Text(
                    text = line.removePrefix("### "),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(vertical = SpacingTokens.xs),
                )

                line.startsWith("- ") -> Text(
                    text = "• ${line.removePrefix("- ")}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = SpacingTokens.xxs),
                )

                line.startsWith("> ") -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = ShapeTokens.extraSmallShape,
                    modifier = Modifier.padding(vertical = SpacingTokens.xxs),
                ) {
                    Text(
                        text = line.removePrefix("> "),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(SpacingTokens.md),
                    )
                }

                else -> Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = SpacingTokens.xxs),
                )
            }
        }
    }
}