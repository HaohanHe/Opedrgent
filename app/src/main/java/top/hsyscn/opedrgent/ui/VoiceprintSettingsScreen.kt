@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

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
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.hsyscn.opedrgent.stt.VoiceprintManager
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.BgGray
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey

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
                title = { Text("声纹识别", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddVoiceprint,
                containerColor = AccentBlue,
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加声纹", tint = Color.White)
            }
        },
        containerColor = BgGray,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                text = "已注册说话人",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                color = TextDark,
            )
            Spacer(Modifier.height(12.dp))

            if (speakers.isEmpty()) {
                EmptySpeakerList()
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(speakers, key = { it.id }) { speaker ->
                        SpeakerCard(
                            name = speaker.name,
                            sampleCount = speaker.samplePaths.size,
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
            title = { Text("删除声纹") },
            text = { Text("确定删除说话人 \"$speakerName\" 的声纹数据吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        voiceprintManager.deleteSpeaker(speakerId)
                        speakers = voiceprintManager.listSpeakers()
                        showDeleteDialog = null
                    },
                ) {
                    Text("删除", color = Color(0xFFE53935))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun SpeakerCard(
    name: String,
    sampleCount: Int,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(11.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White,
            contentColor = TextDark,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = TextDark,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "$sampleCount 个样本",
                    fontSize = 12.sp,
                    color = TextGrey,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = Color.Gray,
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = TextGrey.copy(alpha = 0.4f),
            modifier = Modifier.size(64.dp),
        )
        Text(
            text = "暂无已注册说话人",
            fontSize = 16.sp,
            color = TextGrey,
        )
        Text(
            text = "点击右下角 + 按钮添加声纹",
            fontSize = 13.sp,
            color = TextGrey.copy(alpha = 0.7f),
        )
    }
}
