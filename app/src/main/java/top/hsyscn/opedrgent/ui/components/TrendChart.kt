package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.hsyscn.opedrgent.cultivation.model.DimensionTrend
import top.hsyscn.opedrgent.cultivation.model.FollowUpTrend
import top.hsyscn.opedrgent.cultivation.model.TrendDirection
import top.hsyscn.opedrgent.cultivation.model.TrendInsights
import top.hsyscn.opedrgent.cultivation.model.TrendPoint
import top.hsyscn.opedrgent.ui.theme.PrimaryBrand
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

/**
 * 批判镜「行为维度长期趋势图」卡片组件。
 *
 * 接收 [TrendInsights] 冻结数据契约，纯确定性可视化：
 * - 不做任何语义判定
 * - 不内置维度词表
 * - 方向标签仅为中性描述（增多/减少/持平/新近）
 *
 * @param insights 趋势数据（由 cultivation.model 提供）
 * @param modifier 外部修饰符
 */
@Composable
fun TrendCard(
    insights: TrendInsights,
    modifier: Modifier = Modifier,
) {
    val trends = insights.trends
    val followUp = insights.followUpTrend

    if (trends.isEmpty() && followUp == null) {
        // 空状态：如实说明，不夸大
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = ShapeTokens.mediumShape,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingTokens.xl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "累计两次以上复盘并包含行为维度后，趋势将在此呈现",
                    style = MaterialTheme.typography.bodyMedium,
                    color = themeTextGrey(),
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ShapeTokens.mediumShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(SpacingTokens.lg),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.lg),
        ) {
            // 各行为维度子卡
            trends.forEach { trend ->
                DimensionTrendSubCard(trend = trend)
            }

            // 已完成跟进小节
            followUp?.let { fu ->
                FollowUpTrendSection(followUp = fu)
            }
        }
    }
}

/**
 * 单个行为维度的趋势子卡。
 * 头部：维度名 + 合计次数 + 方向标签；下方：柱形趋势图。
 */
@Composable
private fun DimensionTrendSubCard(
    trend: DimensionTrend,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        // 头部行：维度名 + 合计 + 方向标签
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = trend.dimension,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )

            Text(
                text = "合计 ${trend.total} 次",
                style = MaterialTheme.typography.bodySmall,
                color = themeTextGrey(),
            )

            Spacer(Modifier.width(SpacingTokens.sm))

            DirectionChip(direction = trend.direction)
        }

        // 柱形图
        TrendBarChart(
            points = trend.points,
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp),
        )
    }
}

/**
 * 已完成跟进小节。
 */
@Composable
private fun FollowUpTrendSection(
    followUp: FollowUpTrend,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "已完成跟进",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = "合计 ${followUp.totalDone} 次",
                style = MaterialTheme.typography.bodySmall,
                color = themeTextGrey(),
            )
        }

        TrendBarChart(
            points = followUp.points,
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp),
        )
    }
}

/**
 * 方向标签小胶囊。中性词，不做价值判断。
 */
@Composable
private fun DirectionChip(direction: TrendDirection) {
    val (label, chipBg, chipText) = when (direction) {
        TrendDirection.UP -> Triple("增多", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        TrendDirection.DOWN -> Triple("减少", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        TrendDirection.FLAT -> Triple("持平", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        TrendDirection.NEW -> Triple("新近", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Box(
        modifier = Modifier
            .clip(ShapeTokens.extraSmallShape)
            .background(chipBg)
            .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = chipText,
        )
    }
}

/**
 * 通用柱形趋势图（Canvas 绘制）。
 *
 * 规则：
 * - 等距 x 分布各桶，自基线向上画柱
 * - 柱顶标计数数字
 * - 首末桶标 bucketLabel（中间桶不标，避免窄屏重叠）
 * - 仅 1 个点时画单点 + 下方居中标签
 * - 主色画数据，发丝灰画基线
 */
@Composable
private fun TrendBarChart(
    points: List<TrendPoint>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val availableWidth = maxWidth
        val isNarrow = availableWidth < 360.dp

        val density = LocalDensity.current
        val barColor = PrimaryBrand
        val baselineColor = themeTextGrey().copy(alpha = 0.3f)
        val countLabelColor = MaterialTheme.colorScheme.onSurface
        val bucketLabelColor = themeTextGrey()

        // 预计算像素尺寸（与 Canvas 高度 90.dp 对应）
        val chartHeightPx = with(density) { 90.dp.toPx() }
        val baselineYPx = chartHeightPx - with(density) { 18.dp.toPx() } // 底部留标签空间
        val topPaddingPx = with(density) { 16.dp.toPx() } // 顶部留计数标签空间
        val maxBarHeightPx = baselineYPx - topPaddingPx

        val maxCount = remember(points) { points.maxOfOrNull { it.count } ?: 0 }

        // 文字大小（窄屏缩小，避免重叠）
        val countTextSizePx = with(density) { if (isNarrow) 10.sp.toPx() else 11.sp.toPx() }
        val bucketTextSizePx = with(density) { if (isNarrow) 9.sp.toPx() else 10.sp.toPx() }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp),
        ) {
            val width = size.width

            if (points.isEmpty()) return@Canvas

            // 基线（发丝灰）
            drawLine(
                color = baselineColor,
                start = Offset(0f, baselineYPx),
                end = Offset(width, baselineYPx),
                strokeWidth = with(density) { 1.dp.toPx() },
            )

            if (points.size == 1) {
                // 单点情况：画一个圆点 + 标签
                val point = points[0]
                val centerX = width / 2f
                val dotRadius = with(density) { 4.dp.toPx() }
                val dotY = baselineYPx - (if (maxCount > 0) maxBarHeightPx * 0.4f else 0f)

                drawCircle(
                    color = barColor,
                    radius = dotRadius,
                    center = Offset(centerX, dotY),
                )

                // 柱顶计数（居中）
                drawCountLabel(
                    count = point.count,
                    x = centerX,
                    y = dotY - dotRadius - with(density) { 4.dp.toPx() },
                    color = countLabelColor,
                    textSizePx = countTextSizePx,
                )

                // 桶标签（居中下方）
                drawBucketLabel(
                    label = point.bucketLabel,
                    x = centerX,
                    y = baselineYPx + with(density) { 12.dp.toPx() },
                    color = bucketLabelColor,
                    textSizePx = bucketTextSizePx,
                    alignment = LabelAlign.CENTER,
                )
                return@Canvas
            }

            // 多桶柱形
            val n = points.size
            val slotWidth = width / n.toFloat()
            val barWidthPx = slotWidth * 0.6f // 柱宽占槽位 60%，留间距

            points.forEachIndexed { index, point ->
                val slotCenterX = slotWidth * index + slotWidth / 2f
                val barLeft = slotCenterX - barWidthPx / 2f
                val barRight = slotCenterX + barWidthPx / 2f

                val barHeight = if (maxCount > 0) {
                    (point.count.toFloat() / maxCount.toFloat()) * maxBarHeightPx
                } else {
                    0f
                }

                val barTopY = baselineYPx - barHeight

                // 画柱（顶部圆角）
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(barLeft, barTopY),
                    size = Size(barWidthPx, barHeight),
                    cornerRadius = CornerRadius(
                        x = with(density) { 2.dp.toPx() },
                        y = with(density) { 2.dp.toPx() },
                    ),
                )

                // 柱顶计数标签（居中于柱）
                drawCountLabel(
                    count = point.count,
                    x = slotCenterX,
                    y = barTopY - with(density) { 3.dp.toPx() },
                    color = countLabelColor,
                    textSizePx = countTextSizePx,
                )

                // 首末桶标 bucketLabel（首桶左对齐贴左柱边，末桶右对齐贴右柱边）
                if (index == 0 || index == n - 1) {
                    drawBucketLabel(
                        label = point.bucketLabel,
                        x = if (index == 0) barLeft else barRight,
                        y = baselineYPx + with(density) { 12.dp.toPx() },
                        color = bucketLabelColor,
                        textSizePx = bucketTextSizePx,
                        alignment = if (index == 0) LabelAlign.LEFT else LabelAlign.RIGHT,
                    )
                }
            }
        }
    }
}

/** 标签对齐方式。 */
private enum class LabelAlign { LEFT, CENTER, RIGHT }

/** 在 Canvas 上画柱顶计数数字（始终居中于 x）。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCountLabel(
    count: Int,
    x: Float,
    y: Float,
    color: Color,
    textSizePx: Float,
) {
    val text = count.toString()
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        textSize = textSizePx
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

/** 在 Canvas 上画桶标签，按 [alignment] 对齐。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBucketLabel(
    label: String,
    x: Float,
    y: Float,
    color: Color,
    textSizePx: Float,
    alignment: LabelAlign,
) {
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        textSize = textSizePx
        isAntiAlias = true
        textAlign = when (alignment) {
            LabelAlign.LEFT -> android.graphics.Paint.Align.LEFT
            LabelAlign.CENTER -> android.graphics.Paint.Align.CENTER
            LabelAlign.RIGHT -> android.graphics.Paint.Align.RIGHT
        }
    }
    drawContext.canvas.nativeCanvas.drawText(label, x, y, paint)
}

/** Compose Color → Int ARGB，供 android.graphics.Paint 使用。 */
private fun Color.toArgb(): Int {
    return android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    )
}
