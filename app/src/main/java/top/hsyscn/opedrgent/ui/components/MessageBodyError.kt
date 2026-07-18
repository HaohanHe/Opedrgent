package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.themeTextDark

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBodyError(
    errorText: String,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val maxLines = remember { mutableIntStateOf(5) }
    val shouldShowExpandButton = errorText.lines().size > 5

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShapeTokens.mediumShape)
            .background(MaterialTheme.colorScheme.errorContainer)
            .combinedClickable(
                onClick = { },
                onLongClick = {
                    clipboardManager.setText(AnnotatedString(errorText))
                    scope.launch {
                        snackbarHostState?.showSnackbar(
                            message = context.getString(R.string.msg_body_copied_error),
                            duration = SnackbarDuration.Short,
                        )
                    }
                },
            )
            .padding(SpacingTokens.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = stringResource(R.string.msg_body_cd_error),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(SpacingTokens.sm))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
        ) {
            Text(
                text = if (expanded || !shouldShowExpandButton) {
                    errorText
                } else {
                    errorText.lines().take(5).joinToString("\n")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = if (expanded) Int.MAX_VALUE else maxLines.intValue,
            )
            if (shouldShowExpandButton && !expanded) {
                Spacer(Modifier.height(SpacingTokens.xs))
                Text(
                    text = stringResource(R.string.msg_body_view_all),
                    style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .clip(ShapeTokens.extraSmallShape)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f))
                        .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs)
                        .clickable { expanded = true },
                )
            }
        }
    }
}
