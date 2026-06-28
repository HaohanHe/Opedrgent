package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

@Composable
fun MessageBodyConfigUpdate(
    configName: String,
    oldValue: String,
    newValue: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShapeTokens.mediumShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(SpacingTokens.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        Icon(
            imageVector = Icons.Default.Tune,
            contentDescription = "配置更新",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(SpacingTokens.xs))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) {
                    append(configName)
                }
                append(" 已从 ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)) {
                    append(oldValue)
                }
                append(" 更改为 ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)) {
                    append(newValue)
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = themeTextGrey(),
        )
    }
}
