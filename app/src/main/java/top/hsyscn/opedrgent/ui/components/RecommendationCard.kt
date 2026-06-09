package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.hsyscn.opedrgent.intelligence.FormattedRecommendation
import top.hsyscn.opedrgent.intelligence.PushNotificationHelper
import top.hsyscn.opedrgent.intelligence.Recommendation
import top.hsyscn.opedrgent.intelligence.DailyReview as IntelligenceDailyReview
import top.hsyscn.opedrgent.ui.theme.CardWhite
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import kotlin.math.roundToInt

/**
 * 推荐卡片 — 首页展示智能推荐的核心 UI 组件。
 *
 * ## 设计规范
 * - 卡片圆角 14dp
 * - 左侧彩色竖条（根据 type 不同颜色）
 * - emoji 图标 + 标题 + 描述文字
 * - 右侧操作按钮 + 关闭按钮
 * - 支持滑动关闭（左滑 dismiss）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecommendationCard(
    recommendation: Recommendation,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pushHelper = remember { PushNotificationHelper(context) }
    val formatted = remember(recommendation) { pushHelper.formatRecommendation(recommendation) }

    RecommendationCardInternal(
        formatted = formatted,
        onClick = onClick,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

/**
 * 推荐卡片内部实现 — 接受已格式化的数据，支持滑动关闭。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecommendationCardInternal(
    formatted: FormattedRecommendation,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val dismissWidth = with(density) { 80.dp.toPx() }

    val draggableState = remember {
        AnchoredDraggableState(
            initialValue = 0,
            anchorsBuilder = {
                DraggableAnchors {
                    anchorPosition(0, 0f)       // 正常位置
                    anchorPosition(1, -dismissWidth) // 滑动关闭位置
                }
            },
            positionalThreshold = { distance -> distance * 0.5f },
            velocityThreshold = { Float.MAX_VALUE },
        )
    }

    // 当滑动到关闭位置时触发 dismiss
    LaunchedEffect(draggableState.currentValue) {
        if (draggableState.currentValue == 1) {
            onDismiss()
        }
    }

    val offsetX by animateFloatAsState(
        targetValue = draggableState.offset,
        label = "card_offset",
    )

    val barColor = Color(formatted.color)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onClick,
    ) {
        Box(modifier = Modifier.padding(start = 0.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .anchoredDraggable(draggableState, Orientation.Horizontal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧彩色竖条
                Box(
                    modifier = Modifier
                        .height(56.dp)
                        .width(4.dp)
                        .clip(RoundedCornerShape(topStart = 14.dp, bottomEnd = 0.dp))
                        .background(barColor),
                )

                Spacer(Modifier.width(10.dp))

                // Emoji 图标
                Text(
                    text = formatted.icon,
                    fontSize = 22.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )

                // 标题 + 描述
                Column(
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp, end = 4.dp),
                ) {
                    Text(
                        text = formatted.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = TextDark,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = formatted.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextGrey,
                        maxLines = 2,
                        lineHeight = 16.sp,
                    )
                }

                // 操作按钮
                TextButton(
                    onClick = onClick,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        text = formatted.actionLabel,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = barColor,
                    )
                }

                // 关闭按钮
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = TextGrey,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * 推荐列表组件 — 在首页展示多条推荐的容器。
 *
 * 自动处理空状态和关闭操作。
 */
@Composable
fun RecommendationList(
    recommendations: List<Recommendation>,
    onItemClick: (Recommendation) -> Unit,
    onDismissItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dismissedIds by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(recommendations) {
        dismissedIds = emptySet()
    }

    val visibleRecs = recommendations.filter { it.id !in dismissedIds }

    if (visibleRecs.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        visibleRecs.forEach { rec ->
            key(rec.id) {
                RecommendationCard(
                    recommendation = rec,
                    onClick = { onItemClick(rec) },
                    onDismiss = { dismissedIds += rec.id; onDismissItem(rec.id) },
                )
            }
        }
    }
}

/**
 * 每日回顾卡片 — 展示今日使用统计的专用卡片。
 */
@Composable
fun DailyReviewCard(
    dailyReview: IntelligenceDailyReview?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (dailyReview == null) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧紫色竖条
            Box(
                modifier = Modifier
                    .height(56.dp)
                    .width(4.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, bottomEnd = 0.dp))
                    .background(Color(0xFF9B59B6)),
            )

            Spacer(Modifier.width(10.dp))

            Text(text = "📊", fontSize = 22.sp, modifier = Modifier.padding(end = 8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "今日回顾 — ${dailyReview.date}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = TextDark,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("笔记 ${dailyReview.notesCreated} · AI 对话 ${dailyReview.aiChats}")
                        if (dailyReview.recordings > 0) append(" · 录音 ${dailyReview.recordings}")
                        dailyReview.topNote?.let { append("\n亮点: $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextGrey,
                    maxLines = 2,
                )
            }

            TextButton(onClick = onClick) {
                Text("查看详情", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Color(0xFF9B59B6))
            }
        }
    }
}
