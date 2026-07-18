package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 消息头部：头像 + 名称 + 时间戳。
 *
 * 用于 AI 消息上方，标识说话人身份。用户消息不使用此组件。
 */
@Composable
fun MessageHeader(
    avatar: ImageVector,
    name: String,
    timestamp: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(start = SpacingTokens.xs, end = SpacingTokens.xs, bottom = SpacingTokens.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        Box(
            modifier = Modifier
                .size(SpacingTokens.xxl)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = avatar,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(SpacingTokens.md),
            )
        }
        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!timestamp.isNullOrBlank()) {
                Text(
                    text = timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 将消息时间戳（毫秒）格式化为 HH:mm。 */
fun formatMessageTime(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
