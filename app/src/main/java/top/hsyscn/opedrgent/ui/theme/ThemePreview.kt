package top.hsyscn.opedrgent.ui.theme

import android.content.res.Configuration
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.R

/**
 * 主题色板预览，用于在 IDE 中快速检查浅色/深色模式下的颜色与 token。
 */
@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ThemePalettePreview() {
    OpedrgentTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingTokens.lg)
                .background(MaterialTheme.colorScheme.background),
        ) {
            Text(
                text = stringResource(R.string.theme_preview_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(SpacingTokens.md))

            ColorRow("Primary", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
            ColorRow("Secondary", MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary)
            ColorRow("Tertiary", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)
            ColorRow("Surface", MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)
            ColorRow("Surface Variant", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            ColorRow("Background", MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.onBackground)
            ColorRow("Error", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)

            Spacer(modifier = Modifier.height(SpacingTokens.md))
            Text(
                text = stringResource(R.string.theme_preview_semantic_colors),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(SpacingTokens.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                ColorSwatch("Accent", MaterialTheme.customColors.accentOrange)
                ColorSwatch("Success", MaterialTheme.customColors.successGreen)
                ColorSwatch("Danger", MaterialTheme.customColors.dangerRed)
                ColorSwatch("Sprout", MaterialTheme.customColors.sproutQuoteBg)
            }
        }
    }
}

@Composable
private fun ColorRow(name: String, background: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpacingTokens.xs),
        shape = MaterialTheme.shapes.small,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .padding(SpacingTokens.sm),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(text = name, color = content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ColorSwatch(name: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(color, RoundedCornerShape(ShapeTokens.small)),
        )
        Spacer(modifier = Modifier.height(SpacingTokens.xs))
        Text(text = name, style = MaterialTheme.typography.labelSmall)
    }
}
