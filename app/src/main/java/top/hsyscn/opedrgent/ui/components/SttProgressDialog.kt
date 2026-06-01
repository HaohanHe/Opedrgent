package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.ui.SttProgressState

@Composable
fun SttProgressDialog(
    progressState: SttProgressState,
    downloadProgress: Float? = null,
    onCancel: () -> Unit,
) {
    if (progressState == SttProgressState.IDLE || progressState == SttProgressState.DONE) return

    AlertDialog(
        onDismissRequest = {},
        title = { Text("正在处理...") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(progress = when (progressState) {
                    SttProgressState.DOWNLOADING_MODEL -> downloadProgress ?: 0f
                    else -> null
                })

                Spacer(Modifier.height(16.dp))

                Text(
                    text = when (progressState) {
                        SttProgressState.DOWNLOADING_MODEL -> "正在下载语音模型..."
                        SttProgressState.EXTRACTING_AUDIO -> "正在提取音频..."
                        SttProgressState.RECOGNIZING -> "正在进行语音识别..."
                        SttProgressState.ERROR -> "处理出错"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )

                if (progressState == SttProgressState.DOWNLOADING_MODEL && downloadProgress != null) {
                    Text(
                        text = "${(downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (progressState != SttProgressState.ERROR) {
                TextButton(onClick = onCancel) {
                    Text("取消")
                }
            } else {
                TextButton(onClick = onCancel) {
                    Text("关闭")
                }
            }
        },
    )
}
