package top.hsyscn.opedrgent.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

@Composable
fun AudioPickerDialog(
    onFileSelected: (Uri) -> Unit,
    onDismiss: () -> Unit,
    onRealtimeRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let(onFileSelected)
    }

    var visible by remember { mutableStateOf(true) }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { it / 2 },
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
        ) + fadeIn(),
    ) {
        val dialogCd = stringResource(R.string.audio_picker_cd_dialog)
        Surface(
            shape = ShapeTokens.extraLargeShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = modifier
                .semantics { contentDescription = dialogCd }
                .padding(horizontal = SpacingTokens.xl),
        ) {
            Column(
                modifier = Modifier.padding(SpacingTokens.xl),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "\uD83C\uDFA4",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.width(SpacingTokens.md))
                    Text(
                        text = stringResource(R.string.audio_picker_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(Modifier.height(SpacingTokens.xl))

                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.md)) {
                    val uploadFileCd = stringResource(R.string.audio_picker_cd_upload_file)
                    Card(
                        shape = ShapeTokens.largeShape,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ShapeTokens.largeShape)
                            .clickable(
                                indication = ripple(bounded = true, radius = SpacingTokens.xl),
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                onClick = { launcher.launch("*/*") },
                            )
                            .semantics {
                                contentDescription = uploadFileCd
                                role = androidx.compose.ui.semantics.Role.Button
                            },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(SpacingTokens.lg),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AudioFile,
                                contentDescription = stringResource(R.string.audio_picker_cd_upload),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(SpacingTokens.xxl),
                            )
                            Spacer(Modifier.width(SpacingTokens.lg))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.audio_picker_upload_file),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(SpacingTokens.xxs))
                                Text(
                                    text = stringResource(R.string.audio_picker_supported_formats),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = stringResource(R.string.cd_enter),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    val recordRealtimeCd = stringResource(R.string.audio_picker_cd_record_realtime)
                    Card(
                        shape = ShapeTokens.largeShape,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ShapeTokens.largeShape)
                            .clickable(
                                indication = ripple(bounded = true, radius = SpacingTokens.xl),
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                onClick = {
                                    onRealtimeRecording()
                                    onDismiss()
                                },
                            )
                            .semantics {
                                contentDescription = recordRealtimeCd
                                role = androidx.compose.ui.semantics.Role.Button
                            },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(SpacingTokens.lg),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = stringResource(R.string.audio_picker_cd_record),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(SpacingTokens.xxl),
                            )
                            Spacer(Modifier.width(SpacingTokens.lg))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.audio_picker_realtime_record),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(SpacingTokens.xxs))
                                Text(
                                    text = stringResource(R.string.audio_picker_realtime_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = stringResource(R.string.audio_picker_cd_record),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(SpacingTokens.lg))

                Text(
                    text = stringResource(R.string.audio_picker_local_process_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(SpacingTokens.md))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val closeDialogCd = stringResource(R.string.audio_picker_cd_close_dialog)
                    IconButton(
                        onClick = {
                            visible = false
                            onDismiss()
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .semantics {
                                contentDescription = closeDialogCd
                            },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = stringResource(R.string.action_cancel),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioPickerDialogPreview() {
    AudioPickerDialog(
        onFileSelected = {},
        onDismiss = {},
        onRealtimeRecording = {},
    )
}
