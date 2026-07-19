package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import top.hsyscn.opedrgent.ui.theme.SizeTokens

@Composable
fun BalloonEmptyIllustration(modifier: Modifier = Modifier) {
    val strokeColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = modifier.size(SizeTokens.emptyIllustrationSize)) {
        val strokeWidth = SizeTokens.emptyIllustrationStrokeWidth.toPx()
        val thinStrokeWidth = SizeTokens.emptyIllustrationThinStroke.toPx()
        val style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        val balloonRadiusMd = SizeTokens.emptyIllustrationBalloonRadiusMd.toPx()
        val balloonRadiusLg = SizeTokens.emptyIllustrationBalloonRadiusLg.toPx()
        val balloonRadiusSm = SizeTokens.emptyIllustrationBalloonRadiusSm.toPx()

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
            radius = balloonRadiusMd,
            center = Offset(size.width * 0.30f, size.height * 0.25f),
            style = style
        )
        // 气球1 线
        drawLine(
            color = strokeColor,
            start = Offset(size.width * 0.30f, size.height * 0.25f + balloonRadiusMd),
            end = Offset(size.width * 0.42f, size.height * 0.78f),
            strokeWidth = thinStrokeWidth,
            cap = StrokeCap.Round
        )

        // 气球2 - 中（稍高一点）
        drawCircle(
            color = strokeColor,
            radius = balloonRadiusLg,
            center = Offset(size.width * 0.50f, size.height * 0.18f),
            style = style
        )
        // 气球2 线
        drawLine(
            color = strokeColor,
            start = Offset(size.width * 0.50f, size.height * 0.18f + balloonRadiusLg),
            end = Offset(size.width * 0.50f, size.height * 0.78f),
            strokeWidth = thinStrokeWidth,
            cap = StrokeCap.Round
        )

        // 气球3 - 右
        drawCircle(
            color = strokeColor,
            radius = balloonRadiusSm,
            center = Offset(size.width * 0.72f, size.height * 0.28f),
            style = style
        )
        // 气球3 线
        drawLine(
            color = strokeColor,
            start = Offset(size.width * 0.72f, size.height * 0.28f + balloonRadiusSm),
            end = Offset(size.width * 0.58f, size.height * 0.78f),
            strokeWidth = thinStrokeWidth,
            cap = StrokeCap.Round
        )
    }
}
