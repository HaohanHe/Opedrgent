package top.hsyscn.opedrgent.ui

import top.hsyscn.opedrgent.ui.theme.SuccessGreen
import top.hsyscn.opedrgent.ui.theme.AccentOrange
import top.hsyscn.opedrgent.ui.theme.AccentPurple
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.storage.HippocampusIndex
import top.hsyscn.opedrgent.storage.IndexedItem
import top.hsyscn.opedrgent.storage.SourceType
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.ElevationTokens
import java.text.SimpleDateFormat
import java.util.*

private val hippocampusTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HippocampusScreen(
    hippocampus: HippocampusIndex,
    onBack: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf<SourceType?>(null) }
    var items by remember { mutableStateOf<List<IndexedItem>>(emptyList()) }
    val scope = rememberCoroutineScope()

    // 初始加载 + 搜索/筛选变化时刷新（IO 线程 + 防抖）
    LaunchedEffect(searchQuery, selectedType) {
        if (searchQuery.isNotBlank()) delay(300) // 搜索防抖 300ms
        items = withContext(Dispatchers.IO) {
            val typeFilter = selectedType
            when {
                searchQuery.isNotBlank() -> hippocampus.query(searchQuery.trim())
                typeFilter != null -> hippocampus.getAllByType(typeFilter)
                else -> hippocampus.getAll()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶部栏
        TopAppBar(
            title = { Text(stringResource(R.string.title_hippocampus), fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                }
            },
            actions = {
                Text(
                    text = stringResource(R.string.hippocampus_1_tiao, items.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = SpacingTokens.lg),
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        // 搜索栏
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.hippocampus_sou_suo_ji_yi)) },
            leadingIcon = { Icon(Icons.Default.Search, stringResource(R.string.action_search)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                    Icon(Icons.Default.Close, stringResource(R.string.hippocampus_qing_chu))
                }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.lg)
                .height(48.dp),
            shape = ShapeTokens.smallShape,
            singleLine = true,
        )

        Spacer(Modifier.height(SpacingTokens.sm))

        // 类型筛选 Chip 行
        TypeFilterRow(
            selectedType = selectedType,
            onTypeSelected = { selectedType = it },
        )

        Spacer(Modifier.height(SpacingTokens.sm))

        // 索引列表
        if (items.isEmpty()) {
            EmptyHippocampusState()
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = SpacingTokens.lg, vertical = SpacingTokens.sm),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
            ) {
                items(items, key = { it.id }) { item ->
                    HippocampusItemCard(
                        item = item,
                        onDelete = {
                            scope.launch {
                                hippocampus.delete(item.id)
                                items = items.filter { it.id != item.id }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TypeFilterRow(
    selectedType: SourceType?,
    onTypeSelected: (SourceType?) -> Unit,
) {
    val types = listOf(null) + listOf(
        SourceType.NOTE,
        SourceType.CONVERSATION,
        SourceType.RECORDING,
        SourceType.SPROUT,
        SourceType.INTERVIEW,
    )
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = AccentBlue,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )

    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SpacingTokens.lg),
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        types.forEach { type ->
            val label = when (type) {
                null -> stringResource(R.string.hippocampus_quan_bu)
                SourceType.NOTE -> stringResource(R.string.hippocampus_bi_ji)
                SourceType.CONVERSATION -> stringResource(R.string.hippocampus_dui_hua)
                SourceType.RECORDING -> stringResource(R.string.hippocampus_lu_yin)
                SourceType.SPROUT -> stringResource(R.string.hippocampus_fa_ya)
                SourceType.INTERVIEW -> stringResource(R.string.hippocampus_mian_shi)
                SourceType.USER_MEMORY -> stringResource(R.string.hippocampus_ji_yi)
                SourceType.USER_PREFERENCES -> stringResource(R.string.hippocampus_pian_hao)
            }
            val isSelected = type == selectedType
            FilterChip(
                selected = isSelected,
                onClick = { onTypeSelected(type) },
                label = {
                    Text(
                        label,
                        style = if (isSelected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                    )
                },
                colors = chipColors,
                shape = ShapeTokens.largeShape,
                border = if (isSelected) null else BorderStroke(
                    SizeTokens.borderWidth,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HippocampusItemCard(
    item: IndexedItem,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    @Suppress("DEPRECATION")
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                showDeleteConfirm = true
                false
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color by animateColorAsState(
                when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                },
                label = "swipe_bg",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color, ShapeTokens.mediumShape)
                    .padding(horizontal = SpacingTokens.xl),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cd_delete),
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        Card(
            shape = ShapeTokens.mediumShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(SpacingTokens.md)) {
                // 第一行：来源类型标签 + 标题 + 时间
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 来源类型标签
                    SourceTypeChip(sourceType = item.sourceType)

                    Spacer(Modifier.width(SpacingTokens.md))

                    // 标题
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    // 时间
                    Text(
                        text = formatHippocampusTime(item.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(SpacingTokens.sm))

                // 摘要
                if (item.summary.isNotEmpty()) {
                    Text(
                        text = item.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 关键词
                if (item.keywords.isNotEmpty()) {
                    Spacer(Modifier.height(SpacingTokens.xs))
                    val keywords = item.keywords.split(",").filter { it.isNotBlank() }
                    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
                        keywords.take(4).forEach { kw ->
                            Surface(
                                color = AccentBlue.copy(alpha = 0.08f),
                                shape = ShapeTokens.smallShape,
                            ) {
                                Text(
                                    kw,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AccentBlue,
                                    modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs),
                                )
                            }
                        }
                        if (keywords.size > 4) {
                            Text(
                                "+${keywords.size - 4}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = SpacingTokens.xxs),
                            )
                        }
                    }
                }
            }
        }
    }

    // 删除确认对话框
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.hippocampus_shan_chu_suo_yin)) },
            text = { Text(stringResource(R.string.hippocampus_que_ding_yao_shan_chu_1_de, item.title)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SourceTypeChip(sourceType: SourceType) {
    val (color, labelRes) = when (sourceType) {
        SourceType.NOTE -> AccentBlue to R.string.hippocampus_bi_ji
        SourceType.CONVERSATION -> SuccessGreen to R.string.hippocampus_dui_hua
        SourceType.RECORDING -> AccentOrange to R.string.hippocampus_lu_yin
        SourceType.SPROUT -> AccentPurple to R.string.hippocampus_fa_ya
        SourceType.INTERVIEW -> AccentBlue to R.string.hippocampus_mian_shi
        SourceType.USER_MEMORY -> MaterialTheme.colorScheme.outline to R.string.hippocampus_ji_yi
        SourceType.USER_PREFERENCES -> AccentOrange to R.string.hippocampus_pian_hao
    }
    val label = stringResource(labelRes)

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = ShapeTokens.smallShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Memory,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(SpacingTokens.sm),
            )
            Spacer(Modifier.width(SpacingTokens.xs))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun EmptyHippocampusState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(SpacingTokens.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Memory,
            contentDescription = stringResource(R.string.hippocampus_ji_yi_suo_yin),
            modifier = Modifier.size(SpacingTokens.xxl),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        )
        Spacer(Modifier.height(SpacingTokens.lg))
        Text(
            stringResource(R.string.hippocampus_zan_wu_ji_yi_suo_yin),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SpacingTokens.sm))
        Text(
            stringResource(R.string.hippocampus_dang_nin_chuang_jian_bi_ji),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

private fun formatHippocampusTime(timestamp: Long): String {
    return hippocampusTimeFormat.format(Date(timestamp))
}
