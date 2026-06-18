package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.themeTextDark

/**
 * 播放速度选择 Bottom Sheet。
 *
 * @param currentSpeed 当前播放速度
 * @param onSpeedSelected 速度选择回调
 * @param onDismiss 关闭回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSpeedSheet(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val speeds = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "播放速度",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = themeTextDark(),
                modifier = Modifier.padding(vertical = 12.dp),
            )

            HorizontalDivider()

            speeds.forEach { speed ->
                val label = if (speed == speed.toInt().toFloat()) {
                    "${speed.toInt()}.0x"
                } else {
                    "${speed}x"
                }
                val selected = speed == currentSpeed

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            onClick = { onSpeedSelected(speed) },
                        )
                        .padding(vertical = 4.dp),
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { onSpeedSelected(speed) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = label,
                        fontSize = 16.sp,
                        color = themeTextDark(),
                    )
                    if (selected) {
                        Spacer(Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 8.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text = "取消",
                    fontSize = 16.sp,
                    color = AccentBlue,
                )
            }
        }
    }
}
