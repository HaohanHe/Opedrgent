package top.hsyscn.opedrgent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.cultivation.model.TrendInsights
import top.hsyscn.opedrgent.cultivation.model.growth.GrowthPeriodType
import top.hsyscn.opedrgent.cultivation.model.growth.GrowthReview
import top.hsyscn.opedrgent.cultivation.store.GrowthReviewStore
import top.hsyscn.opedrgent.cultivation.store.ReflectionStore
import top.hsyscn.opedrgent.ui.components.TrendCard
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey
import java.util.Calendar

/** 周期标签：周 → 年+周数；月 → 年+月。 */
private fun periodLabel(type: GrowthPeriodType, startMs: Long, ctx: android.content.Context): String {
    val cal = Calendar.getInstance().apply { timeInMillis = startMs }
    val year = cal.get(Calendar.YEAR)
    return if (type == GrowthPeriodType.WEEK) {
        ctx.getString(R.string.growth_review_week_label, year, cal.get(Calendar.WEEK_OF_YEAR))
    } else {
        ctx.getString(R.string.growth_review_month_label, year, cal.get(Calendar.MONTH) + 1)
    }
}

/**
 * 周期性成长回顾（周报/月报）屏。
 *
 * 列表与详情只读渲染 GrowthReviewStore 持久化结果；「生成」经 onRequestGenerate
 * 交回助手会话，由其工具循环调用 growth_review_create（本屏不直接调模型/工具）。
 * 趋势复用 TrendCard，对该周期 records 用 TrendInsights.from 现算。不做关键词判定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrowthReviewScreen(
    onBack: () -> Unit,
    onRequestGenerate: (GrowthPeriodType) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { GrowthReviewStore(context) }
    val reflectionStore = remember { ReflectionStore(context) }

    var periodType by remember { mutableStateOf(GrowthPeriodType.WEEK) }
    var reviews by remember { mutableStateOf<List<GrowthReview>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<GrowthReview?>(null) }

    suspend fun reload() {
        reviews = runCatching { store.listAll() }.getOrDefault(emptyList())
        loading = false
    }
    LaunchedEffect(Unit) { reload() }
    // 从助手生成返回后回到本屏时刷新
    LaunchedEffect(selected) { if (selected == null) reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selected == null) stringResource(R.string.growth_review_title)
                        else periodLabel(selected!!.periodType, selected!!.periodStart, context),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selected != null) selected = null else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        containerColor = themeBgGray(),
    ) { padding ->
        val current = selected
        if (current != null) {
            GrowthDetail(
                review = current,
                reflectionStore = reflectionStore,
                modifier = Modifier.padding(padding),
            )
        } else {
            val filtered = reviews.filter { it.periodType == periodType }
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(SpacingTokens.lg),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
            ) {
                // 周期切换 + 生成本期回顾
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChipRow(
                            weekSelected = periodType == GrowthPeriodType.WEEK,
                            onSelectWeek = { periodType = GrowthPeriodType.WEEK },
                            onSelectMonth = { periodType = GrowthPeriodType.MONTH },
                        )
                    }
                    Spacer(Modifier.height(SpacingTokens.sm))
                    Button(
                        onClick = { onRequestGenerate(periodType) },
                        shape = ShapeTokens.smallShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (periodType == GrowthPeriodType.WEEK) stringResource(R.string.growth_review_generate_week)
                            else stringResource(R.string.growth_review_generate_month),
                        )
                    }
                    Spacer(Modifier.height(SpacingTokens.xs))
                    Text(
                        stringResource(R.string.growth_review_gen_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey(),
                    )
                }

                if (loading) {
                    item { Text(stringResource(R.string.growth_review_empty), color = themeTextGrey()) }
                } else if (filtered.isEmpty()) {
                    item {
                        Spacer(Modifier.height(SpacingTokens.xl))
                        Text(
                            stringResource(R.string.growth_review_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = themeTextGrey(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    items(filtered, key = { it.id }) { review ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = review },
                            shape = ShapeTokens.mediumShape,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Column(modifier = Modifier.padding(SpacingTokens.md)) {
                                Text(
                                    periodLabel(review.periodType, review.periodStart, context),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = themeTextDark(),
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(SpacingTokens.xxs))
                                Text(
                                    if (review.overall.isNotBlank()) review.overall
                                    else stringResource(R.string.growth_review_no_change),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = themeTextGrey(),
                                    maxLines = 3,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipRow(
    weekSelected: Boolean,
    onSelectWeek: () -> Unit,
    onSelectMonth: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
        OutlinedButton(
            onClick = onSelectWeek,
            shape = ShapeTokens.pill,
        ) { Text(stringResource(R.string.growth_review_tab_week)) }
        OutlinedButton(onClick = onSelectMonth, shape = ShapeTokens.pill) {
            Text(stringResource(R.string.growth_review_tab_month))
        }
    }
}

@Composable
private fun GrowthDetail(
    review: GrowthReview,
    reflectionStore: ReflectionStore,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var insights by remember(review.id) { mutableStateOf<TrendInsights?>(null) }
    LaunchedEffect(review.id) {
        val recs = reflectionStore.listRecent(500).filter {
            it.createdAt >= review.periodStart && it.createdAt < review.periodEnd
        }
        insights = TrendInsights.from(recs)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(SpacingTokens.lg),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        item {
            Text(
                periodLabel(review.periodType, review.periodStart, context),
                style = MaterialTheme.typography.titleLarge,
                color = themeTextDark(),
                fontWeight = FontWeight.Bold,
            )
        }
        if (review.overall.isNotBlank()) {
            item { SectionText(stringResource(R.string.growth_review_overall), review.overall) }
        }
        if (review.changes.isNotEmpty()) {
            item { SectionList(stringResource(R.string.growth_review_changes), review.changes) }
        } else {
            item { Text(stringResource(R.string.growth_review_no_change), color = themeTextGrey(), style = MaterialTheme.typography.bodySmall) }
        }
        if (review.strengths.isNotEmpty()) {
            item { SectionList(stringResource(R.string.growth_review_strengths), review.strengths) }
        }
        if (review.focus.isNotEmpty()) {
            item { SectionList(stringResource(R.string.growth_review_focus), review.focus) }
        }
        if (review.evidence.isNotEmpty()) {
            item {
                Spacer(Modifier.height(SpacingTokens.sm))
                Text(stringResource(R.string.growth_review_evidence), style = MaterialTheme.typography.titleSmall, color = themeTextDark())
                Spacer(Modifier.height(SpacingTokens.xs))
            }
            items(review.evidence, key = { it.dimension + it.quote }) { ev ->
                Surface(
                    shape = ShapeTokens.smallShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(SpacingTokens.sm)) {
                        Text(ev.dimension, style = MaterialTheme.typography.labelSmall, color = themeTextGrey())
                        Spacer(Modifier.height(SpacingTokens.xxs))
                        Text(ev.quote, style = MaterialTheme.typography.bodyMedium, color = themeTextDark())
                    }
                }
            }
        }
        item {
            insights?.let { TrendCard(insights = it, modifier = Modifier.padding(vertical = SpacingTokens.sm)) }
        }
    }
}

@Composable
private fun SectionText(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = themeTextDark())
        Spacer(Modifier.height(SpacingTokens.xs))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = themeTextGrey())
    }
}

@Composable
private fun SectionList(title: String, items: List<String>) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = themeTextDark())
        Spacer(Modifier.height(SpacingTokens.xs))
        items.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium, color = themeTextGrey()) }
    }
}
