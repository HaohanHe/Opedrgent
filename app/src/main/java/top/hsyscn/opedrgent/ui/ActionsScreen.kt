@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.action.ActionItem
import top.hsyscn.opedrgent.action.ActionKind
import top.hsyscn.opedrgent.action.ActionStatus
import top.hsyscn.opedrgent.action.ActionStore
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

/**
 * 行动跟进屏：把跨场景产生的「要做的事」收敛到一张清单，完成闭环。
 *
 * 只消费 ActionStore 数据，不做任何内容/关键词判定；状态变更在后台线程，操作进行中防重复点击。
 */
@Composable
fun ActionsScreen(
    onBack: () -> Unit,
    /** 查看复盘：携带 sourceReflectionId 导航到批判镜历史（本阶段只切到历史，不做精确滚动定位）。 */
    onOpenReflection: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ActionStore.getInstance(context) }

    var items by remember { mutableStateOf<List<ActionItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var actingId by remember { mutableStateOf<Long?>(null) }
    var detail by remember { mutableStateOf<ActionItem?>(null) }

    fun reload() {
        loading = true
        scope.launch {
            items = runCatching { store.listAll() }.getOrDefault(emptyList())
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun changeStatus(item: ActionItem, next: ActionStatus) {
        if (actingId != null) return
        actingId = item.id
        scope.launch {
            runCatching { store.updateStatus(item.id, next) }
            actingId = null
            reload()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("行动跟进", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        containerColor = themeBgGray(),
    ) { padding ->
        if (loading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (items.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(SpacingTokens.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "暂无行动项",
                    style = MaterialTheme.typography.titleMedium,
                    color = themeTextGrey(),
                )
                Spacer(Modifier.height(SpacingTokens.sm))
                Text(
                    "复盘或对话中沉淀的待办会汇总到这里，保持克制、不打扰。",
                    style = MaterialTheme.typography.bodySmall,
                    color = themeTextGrey(),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = SpacingTokens.lg),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.lg),
        ) {
            actionGroup(
                title = "待处理",
                list = items.filter { it.status == ActionStatus.OPEN },
                actingId = actingId,
                onOpenDetail = { detail = it },
                onComplete = { changeStatus(it, ActionStatus.DONE) },
                onDefer = { changeStatus(it, ActionStatus.DEFERRED) },
                onReopen = { changeStatus(it, ActionStatus.OPEN) },
            )
            actionGroup(
                title = "已搁置",
                list = items.filter { it.status == ActionStatus.DEFERRED },
                actingId = actingId,
                onOpenDetail = { detail = it },
                onComplete = { changeStatus(it, ActionStatus.DONE) },
                onDefer = { changeStatus(it, ActionStatus.DEFERRED) },
                onReopen = { changeStatus(it, ActionStatus.OPEN) },
            )
            actionGroup(
                title = "已完成",
                list = items.filter { it.status == ActionStatus.DONE },
                actingId = actingId,
                onOpenDetail = { detail = it },
                onComplete = { changeStatus(it, ActionStatus.DONE) },
                onDefer = { changeStatus(it, ActionStatus.DEFERRED) },
                onReopen = { changeStatus(it, ActionStatus.OPEN) },
            )
            item { Spacer(Modifier.height(SpacingTokens.xl)) }
        }
    }

    detail?.let { item ->
        ActionSourceDialog(
            item = item,
            onDismiss = { detail = null },
            onOpenReflection = { onOpenReflection(item.sourceReflectionId) },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.actionGroup(
    title: String,
    list: List<ActionItem>,
    actingId: Long?,
    onOpenDetail: (ActionItem) -> Unit,
    onComplete: (ActionItem) -> Unit,
    onDefer: (ActionItem) -> Unit,
    onReopen: (ActionItem) -> Unit,
) {
    if (list.isEmpty()) return
    item {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = themeTextGrey(),
            modifier = Modifier.padding(top = SpacingTokens.sm),
        )
    }
    items(list, key = { it.id }) { item ->
        ActionRow(
            item = item,
            acting = actingId == item.id,
            onOpenDetail = { onOpenDetail(item) },
            onComplete = { onComplete(item) },
            onDefer = { onDefer(item) },
            onReopen = { onReopen(item) },
        )
    }
}

@Composable
private fun ActionRow(
    item: ActionItem,
    acting: Boolean,
    onOpenDetail: () -> Unit,
    onComplete: () -> Unit,
    onDefer: () -> Unit,
    onReopen: () -> Unit,
) {
    Card(
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(SpacingTokens.lg), verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                KindChip(item.kind)
            }
            val sourceLine = buildString {
                if (item.sourceTypeLabel.isNotBlank()) append(item.sourceTypeLabel)
                if (item.sourceTitle.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(item.sourceTitle)
                }
            }
            if (sourceLine.isNotBlank()) {
                Text(sourceLine, style = MaterialTheme.typography.bodySmall, color = themeTextGrey())
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (item.status) {
                    ActionStatus.OPEN -> {
                        OutlinedButton(
                            onClick = onComplete,
                            enabled = !acting,
                            shape = ShapeTokens.smallShape,
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(SpacingTokens.xs))
                            Text("完成")
                        }
                        OutlinedButton(
                            onClick = onDefer,
                            enabled = !acting,
                            shape = ShapeTokens.smallShape,
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(SpacingTokens.xs))
                            Text("搁置")
                        }
                    }
                    ActionStatus.DEFERRED, ActionStatus.DONE -> {
                        OutlinedButton(
                            onClick = onReopen,
                            enabled = !acting,
                            shape = ShapeTokens.smallShape,
                        ) {
                            Text("重新打开")
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenDetail, enabled = !acting) {
                    Text("查看来源")
                }
            }
        }
    }
}

@Composable
private fun KindChip(kind: ActionKind) {
    val label = when (kind) {
        ActionKind.SAYING -> "替代说法"
        ActionKind.NEXT_STEP -> "下一步"
        ActionKind.GENERAL -> "行动"
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = SpacingTokens.sm)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(ShapeTokens.tag))
            .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs),
    )
}

@Composable
private fun ActionSourceDialog(
    item: ActionItem,
    onDismiss: () -> Unit,
    onOpenReflection: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.sourceTitle.ifBlank { "来源情境" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                if (item.sourceTypeLabel.isNotBlank()) {
                    Text(item.sourceTypeLabel, style = MaterialTheme.typography.labelMedium, color = themeTextGrey())
                }
                Text(
                    item.sourceSnippet.ifBlank { "（无摘录）" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmButton = {
            Row {
                if (item.sourceReflectionId > 0) {
                    TextButton(onClick = onOpenReflection) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(SpacingTokens.xs))
                        Text("查看复盘")
                    }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

/**
 * 首页概览的温和提示：仅当存在 OPEN 行动项时显示「N 项跟进待处理」，点击进入行动跟进屏。
 * 只依据状态计数，不做内容判定；无 OPEN 项时不渲染。
 */
@Composable
fun ActionReminderCard(onClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var openCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        openCount = runCatching { ActionStore.getInstance(context).listOpen().size }.getOrDefault(0)
    }
    if (openCount <= 0) return
    Card(
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(SpacingTokens.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$openCount 项跟进待处理",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            Text(
                "查看",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
