package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import org.json.JSONObject
import top.hsyscn.opedrgent.ui.theme.BubbleBlue
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey

@Composable
fun ToolCallPermissionDialog(
    toolName: String,
    toolDescription: String,
    paramsJson: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onDismiss: () -> Unit,
) {
    val formattedJson = try {
        if (paramsJson.isNotBlank()) {
            JSONObject(paramsJson).toString(2)
        } else {
            "{}"
        }
    } catch (e: Exception) {
        paramsJson
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = "工具调用确认",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF8F9FA))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "工具名称",
                        fontSize = 12.sp,
                        color = TextGrey,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = toolName,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = BubbleBlue,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "工具描述",
                        fontSize = 12.sp,
                        color = TextGrey,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = toolDescription,
                        fontSize = 14.sp,
                        color = TextDark,
                        lineHeight = 20.sp,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "参数",
                        fontSize = 12.sp,
                        color = TextGrey,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = formattedJson,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFEFF2F7))
                            .padding(12.dp),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF374151),
                        lineHeight = 18.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAllow,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BubbleBlue,
                ),
            ) {
                Text(
                    text = "允许",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDeny,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFDC2626),
                ),
            ) {
                Text(
                    text = "拒绝",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFDC2626),
                )
            }
        },
    )
}
