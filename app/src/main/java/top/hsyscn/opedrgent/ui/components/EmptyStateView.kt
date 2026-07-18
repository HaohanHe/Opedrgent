package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.theme.OpedrgentTheme
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

@Composable
fun EmptyStateView(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.height(120.dp),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(modifier = Modifier.height(SpacingTokens.lg))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (subtitle.isNotEmpty()) {
            Spacer(modifier = Modifier.height(SpacingTokens.lg))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(SpacingTokens.xl))
            Button(
                onClick = onAction,
                shape = ShapeTokens.mediumShape,
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Preview(showBackground = true, name = "Light")
@Composable
private fun EmptyStateViewPreview() {
    OpedrgentTheme(darkTheme = false) {
        EmptyStateView(
            icon = {
                Icon(
                    Icons.Default.Inbox,
                    contentDescription = null,
                    modifier = Modifier.size(SizeTokens.emptyStateIcon),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            },
            title = stringResource(R.string.empty_state_title),
            subtitle = stringResource(R.string.empty_state_subtitle),
            actionLabel = stringResource(R.string.empty_state_action),
            onAction = {},
            modifier = Modifier.padding(SpacingTokens.xxl),
        )
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Dark")
@Composable
private fun EmptyStateViewDarkPreview() {
    OpedrgentTheme(darkTheme = true) {
        EmptyStateView(
            icon = {
                Icon(
                    Icons.Default.Inbox,
                    contentDescription = null,
                    modifier = Modifier.size(SizeTokens.emptyStateIcon),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            },
            title = stringResource(R.string.empty_state_title),
            subtitle = stringResource(R.string.empty_state_subtitle),
            actionLabel = stringResource(R.string.empty_state_action),
            onAction = {},
            modifier = Modifier.padding(SpacingTokens.xxl),
        )
    }
}
