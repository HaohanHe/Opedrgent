@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import top.hsyscn.opedrgent.ui.theme.SuccessGreen
import top.hsyscn.opedrgent.ui.theme.DisabledColor
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.stt.VoiceprintManager
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

private val SherpaGreen = SuccessGreen
private val StatGrey = DisabledColor

@Composable
fun VoiceprintSettingsScreen(
    onBack: () -> Unit,
    onAddVoiceprint: () -> Unit,
) {
    val context = LocalContext.current
    val voiceprintManager = remember { VoiceprintManager(context) }
    var speakers by remember { mutableStateOf(voiceprintManager.listSpeakers()) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_voiceprint), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddVoiceprint,
                containerColor = AccentBlue,
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.voiceprint_add), tint = MaterialTheme.colorScheme.onPrimary)
            }
        },
        containerColor = themeBgGray(),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(SpacingTokens.lg),
        ) {
            Text(
                text = stringResource(R.string.voiceprint_registered_speakers),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                color = themeTextDark(),
            )
            Spacer(Modifier.height(SpacingTokens.md))

            if (speakers.isEmpty()) {
                EmptySpeakerList()
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(speakers, key = { it.id }) { speaker ->
                        SpeakerCard(
                            name = speaker.name,
                            sampleCount = speaker.samplePaths.size,
                            embeddingType = speaker.embeddingType,
                            embeddingDim = speaker.embeddingDim,
                            onDelete = {
                                showDeleteDialog = speaker.id
                            },
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog != null) {
        val speakerId = showDeleteDialog!!
        val speakerName = speakers.find { it.id == speakerId }?.name ?: ""
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(stringResource(R.string.voiceprint_delete_title)) },
            text = { Text(stringResource(R.string.voiceprint_delete_confirm, speakerName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        voiceprintManager.deleteSpeaker(speakerId)
                        speakers = voiceprintManager.listSpeakers()
                        showDeleteDialog = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SpeakerCard(
    name: String,
    sampleCount: Int,
    embeddingType: String = VoiceprintManager.EMBEDDING_TYPE_STATISTICAL,
    embeddingDim: Int = VoiceprintManager.EMBEDDING_DIM,
    onDelete: () -> Unit,
) {
    val isSherpa = embeddingType == VoiceprintManager.EMBEDDING_TYPE_SHERPA_ONNX

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.smallShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = themeTextDark(),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(SpacingTokens.md)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = if (isSherpa) SherpaGreen else AccentBlue,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(SpacingTokens.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                    color = themeTextDark(),
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                ) {
                    Text(
                        text = stringResource(R.string.voiceprint_sample_count, sampleCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey(),
                    )
                    Text(
                        text = if (isSherpa) stringResource(R.string.voiceprint_sherpa_label, embeddingDim) else stringResource(R.string.voiceprint_statistical_label, embeddingDim),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSherpa) SherpaGreen else StatGrey,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cd_delete),
                    tint = themeTextGrey(),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptySpeakerList() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = themeTextGrey().copy(alpha = 0.4f),
            modifier = Modifier.size(64.dp),
        )
        Text(
            text = stringResource(R.string.voiceprint_empty),
            style = MaterialTheme.typography.titleMedium,
            color = themeTextGrey(),
        )
        Text(
            text = stringResource(R.string.voiceprint_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = themeTextGrey().copy(alpha = 0.7f),
        )
    }
}
