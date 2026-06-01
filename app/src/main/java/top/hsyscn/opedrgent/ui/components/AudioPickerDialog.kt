package top.hsyscn.opedrgent.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.net.Uri

@Composable
fun AudioPickerDialog(
    onFileSelected: (Uri) -> Unit,
    onDismiss: () -> Unit,
    onRealtimeRecording: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onFileSelected(it) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择输入方式") },
        text = {
            Column {
                OutlinedButton(
                    onClick = { launcher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("上传音频/视频文件")
                }
                Text("支持 MP3, M4A, WAV, MP4 等格式，最大 30 分钟", style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(16.dp))

                OutlinedButton(
                    onClick = {
                        onRealtimeRecording()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("实时录音转文字")
                }
                Text("使用麦克风进行实时语音识别", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
