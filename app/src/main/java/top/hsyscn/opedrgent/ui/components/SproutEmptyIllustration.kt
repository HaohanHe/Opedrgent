package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun SproutEmptyIllustration(modifier: Modifier = Modifier) {
    val strokeColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = modifier.size(100.dp)) {
        val strokeWidth = 2.dp.toPx()
        val style = Stroke(width = strokeWidth, cap = StrokeCap.Round)

        // 土壤线 - 底部弯曲水平线
        drawPath(
            path = androidx.compose.ui.graphics.Path().apply {
                moveTo(10f, size.height * 0.75f)
                cubicTo(
                    size.width * 0.25f, size.height * 0.72f,
                    size.width * 0.75f, size.height * 0.78f,
                    size.width - 10f, size.height * 0.75f
                )
            },
            color = strokeColor,
            style = style
        )

        // 茎
        drawLine(
            color = strokeColor,
            start = Offset(size.width * 0.5f, size.height * 0.75f),
            end = Offset(size.width * 0.5f, size.height * 0.45f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // 左叶子
        drawPath(
            path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.5f, size.height * 0.52f)
                quadraticTo(
                    size.width * 0.25f, size.height * 0.42f,
                    size.width * 0.22f, size.height * 0.55f
                )
                quadraticTo(
                    size.width * 0.30f, size.height * 0.60f,
                    size.width * 0.5f, size.height * 0.52f
                )
            },
            color = strokeColor,
            style = style
        )

        // 右叶子
        drawPath(
            path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.5f, size.height * 0.48f)
                quadraticTo(
                    size.width * 0.75f, size.height * 0.38f,
                    size.width * 0.78f, size.height * 0.51f
                )
                quadraticTo(
                    size.width * 0.70f, size.height * 0.56f,
                    size.width * 0.5f, size.height * 0.48f
                )
            },
            color = strokeColor,
            style = style
        )
    }
}
