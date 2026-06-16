package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun BalloonEmptyIllustration(modifier: Modifier = Modifier) {
    val strokeColor = Color(0xFF1A1A1A)
    Canvas(modifier = modifier.size(100.dp)) {
        val strokeWidth = 2.dp.toPx()
        val style = Stroke(width = strokeWidth, cap = StrokeCap.Round)

        // 花盆 - 梯形底部
        drawPath(
            path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.35f, size.height * 0.78f)
                lineTo(size.width * 0.30f, size.height * 0.95f)
                lineTo(size.width * 0.70f, size.height * 0.95f)
                lineTo(size.width * 0.65f, size.height * 0.78f)
                close()
            },
            color = strokeColor,
            style = style
        )

        // 花盆口
        drawLine(
            color = strokeColor,
            start = Offset(size.width * 0.33f, size.height * 0.78f),
            end = Offset(size.width * 0.67f, size.height * 0.78f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // 气球1 - 左
        drawCircle(
            color = strokeColor,
            radius = 12.dp.toPx(),
            center = Offset(size.width * 0.30f, size.height * 0.25f),
            style = style
        )
        // 气球1 线
        drawLine(
            color = strokeColor,
            start = Offset(size.width * 0.30f, size.height * 0.25f + 12.dp.toPx()),
            end = Offset(size.width * 0.42f, size.height * 0.78f),
            strokeWidth = 1.5f,
            cap = StrokeCap.Round
        )

        // 气球2 - 中（稍高一点）
        drawCircle(
            color = strokeColor,
            radius = 14.dp.toPx(),
            center = Offset(size.width * 0.50f, size.height * 0.18f),
            style = style
        )
        // 气球2 线
        drawLine(
            color = strokeColor,
            start = Offset(size.width * 0.50f, size.height * 0.18f + 14.dp.toPx()),
            end = Offset(size.width * 0.50f, size.height * 0.78f),
            strokeWidth = 1.5f,
            cap = StrokeCap.Round
        )

        // 气球3 - 右
        drawCircle(
            color = strokeColor,
            radius = 11.dp.toPx(),
            center = Offset(size.width * 0.72f, size.height * 0.28f),
            style = style
        )
        // 气球3 线
        drawLine(
            color = strokeColor,
            start = Offset(size.width * 0.72f, size.height * 0.28f + 11.dp.toPx()),
            end = Offset(size.width * 0.58f, size.height * 0.78f),
            strokeWidth = 1.5f,
            cap = StrokeCap.Round
        )
    }
}
