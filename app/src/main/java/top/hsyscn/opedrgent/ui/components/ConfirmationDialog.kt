package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

@Composable
fun ConfirmationDialog(
    request: ConfirmationRequest,
    onConfirm: (selectedOption: String?) -> Unit,
    onDismiss: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var remainingSeconds by remember { mutableIntStateOf(request.timeoutSeconds) }
    var isExpired by remember { mutableStateOf(false) }

    LaunchedEffect(remainingSeconds) {
        if (remainingSeconds > 0) {
            delay(1000)
            remainingSeconds--
        } else if (!isExpired) {
            isExpired = true
            onTimeout()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = ShapeTokens.largeShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(SpacingTokens.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.confirm_dialog_cd_confirm),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(SpacingTokens.md))
                Text(
                    text = request.message,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }

            if (request.detail.isNotBlank()) {
                Spacer(modifier = Modifier.height(SpacingTokens.md))
                Text(
                    text = request.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(SpacingTokens.lg))

            if (request.options.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    request.options.forEach { option ->
                        OutlinedButton(
                            onClick = { onConfirm(option.label) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = ShapeTokens.mediumShape,
                        ) {
                            Text(
                                text = option.label,
                                modifier = Modifier.padding(vertical = SpacingTokens.xs),
                            )
                            if (option.description.isNotBlank()) {
                                Spacer(modifier = Modifier.width(SpacingTokens.sm))
                                Text(
                                    text = option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(SpacingTokens.md))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = ShapeTokens.mediumShape,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.confirm_dialog_cd_cancel),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(SpacingTokens.xs))
                    Text(stringResource(R.string.action_cancel))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (isExpired) {
                        Text(
                            text = stringResource(R.string.state_timeout),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        )
                    } else {
                        CountdownTimer(remainingSeconds = remainingSeconds)
                    }
                }

                Button(
                    onClick = { onConfirm("__confirmed__") },
                    shape = ShapeTokens.mediumShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.confirm_dialog_cd_confirm),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(SpacingTokens.xs))
                    Text(stringResource(R.string.action_confirm))
                }
            }
        }
    }
}

@Composable
private fun CountdownTimer(remainingSeconds: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "countdown")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val countdownLabel = stringResource(R.string.state_countdown_seconds, remainingSeconds)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(ShapeTokens.extraLargeShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
            .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs)
            .then(
                if (remainingSeconds <= 5) {
                    Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        stateDescription = countdownLabel
                    }
                } else {
                    Modifier
                }
            ),
    ) {
        Box(
            modifier = Modifier
                .size(SpacingTokens.sm)
                .clip(CircleShape)
                .background(if (remainingSeconds <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
        )
        Spacer(modifier = Modifier.width(SpacingTokens.sm))
        Text(
            text = "${remainingSeconds}s",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
