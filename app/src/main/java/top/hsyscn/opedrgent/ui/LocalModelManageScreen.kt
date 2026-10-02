package top.hsyscn.opedrgent.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.llm.LocalModelUpdateChecker
import top.hsyscn.opedrgent.llm.ModelDownloadManager
import top.hsyscn.opedrgent.modelreadiness.LocalModelEntry
import top.hsyscn.opedrgent.modelreadiness.ModelReadinessRepository
import top.hsyscn.opedrgent.modelreadiness.ReadyState
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey
import kotlinx.coroutines.launch

/** 格式化模型体积：>=1GB 用 GB，否则 MB。 */
private fun formatSizeMb(mb: Long, ctx: android.content.Context): String =
    if (mb >= 1024) ctx.getString(R.string.local_models_size_gb, mb / 1024.0)
    else ctx.getString(R.string.local_models_size_mb, mb.toInt())

/**
 * 本地模型管理屏：多模型并存后的下载 / 启用 / 删除 / 完整性 / 更新提示。
 * 仅做展示与固定契约调用，不触碰数据层内部；不做任何内容关键词判定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelManageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { ModelReadinessRepository.getInstance(context) }
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<LocalModelEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<LocalModelEntry?>(null) }
    var working by remember { mutableStateOf(false) }

    val snapshot by repo.snapshot.collectAsStateWithLifecycle()

    suspend fun reload() {
        runCatching { repo.listLocalModelEntries() }
            .onSuccess { entries = it; loadError = false }
            .onFailure { loadError = true }
        loading = false
    }

    LaunchedEffect(Unit) {
        repo.refresh()
        reload()
    }
    // 下载完成 / 状态回落到 READY 后刷新列表
    LaunchedEffect(snapshot.llm.state) {
        if (snapshot.llm.state == ReadyState.READY) reload()
    }

    // 已下载模型 id 集合 → 纯本地更新提示（不触网）
    val downloadedIds = entries.filter { it.downloaded }.map { it.id }.toSet()
    val updateHints = remember(downloadedIds) {
        if (downloadedIds.isEmpty()) emptyList()
        else LocalModelUpdateChecker.findUpdates(downloadedIds)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.local_models_title), style = MaterialTheme.typography.headlineLarge)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        containerColor = themeBgGray(),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(SpacingTokens.lg),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        ) {
            item {
                Text(
                    text = stringResource(R.string.local_models_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = themeTextGrey(),
                )
                Spacer(Modifier.height(SpacingTokens.sm))
            }

            // 下载进度条（克制横幅）
            if (snapshot.llm.state == ReadyState.DOWNLOADING) {
                item {
                    val pct = (snapshot.llm.progress * 100).toInt().coerceIn(0, 100)
                    Column {
                        LinearProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(SpacingTokens.xs))
                        Text(
                            text = stringResource(R.string.local_models_downloading, pct),
                            style = MaterialTheme.typography.bodySmall,
                            color = themeTextGrey(),
                        )
                    }
                    Spacer(Modifier.height(SpacingTokens.sm))
                }
            }

            // 离线提示
            if (!snapshot.online) {
                item {
                    Text(
                        text = stringResource(R.string.local_models_downloading_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey(),
                    )
                    Spacer(Modifier.height(SpacingTokens.sm))
                }
            }

            // 更新提示（仅提示，不自动下载）
            if (updateHints.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = ShapeTokens.mediumShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(modifier = Modifier.padding(SpacingTokens.md)) {
                            Text(
                                text = stringResource(R.string.local_models_update_hint_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = themeTextDark(),
                            )
                            Spacer(Modifier.height(SpacingTokens.xs))
                            updateHints.forEach { hint ->
                                Text(
                                    text = hint.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = themeTextGrey(),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(SpacingTokens.sm))
                }
            }

            if (loading) {
                item {
                    Text(stringResource(R.string.local_models_load_error), color = themeTextGrey())
                }
            } else if (loadError) {
                item {
                    Text(stringResource(R.string.local_models_load_error), color = MaterialTheme.colorScheme.error)
                }
            } else if (entries.isEmpty()) {
                item {
                    Text(stringResource(R.string.local_models_empty), color = themeTextGrey())
                }
            } else {
                items(entries, key = { it.id }) { entry ->
                    ModelRow(
                        entry = entry,
                        online = snapshot.online,
                        working = working,
                        onDownload = {
                            if (!working) {
                                working = true
                                repo.startLlm(entry.id)
                                working = false
                            }
                        },
                        onSetActive = {
                            if (!working) {
                                working = true
                                scope.launch {
                                    repo.selectLlm(entry.id)
                                    reload()
                                    working = false
                                }
                            }
                        },
                        onRequestDelete = { pendingDelete = entry },
                    )
                }
            }
        }
    }

    // 删除二次确认
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.local_models_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.local_models_delete_body,
                        target.displayName,
                        formatSizeMb(target.sizeMb, context),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        runCatching { ModelDownloadManager(context).deleteModel(target.id) }
                        repo.refresh()
                        reload()
                    }
                }) {
                    Text(stringResource(R.string.local_models_confirm_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.local_models_cancel))
                }
            },
        )
    }
}

@Composable
private fun ModelRow(
    entry: LocalModelEntry,
    online: Boolean,
    working: Boolean,
    onDownload: () -> Unit,
    onSetActive: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val rowContext = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .padding(SpacingTokens.md)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(
                selected = entry.active,
                onClick = if (entry.downloaded) onSetActive else null,
                enabled = entry.downloaded && !working,
            )
            Spacer(Modifier.width(SpacingTokens.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = themeTextDark(),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(SpacingTokens.xxs))
                Text(
                    text = buildString {
                        append(formatSizeMb(entry.sizeMb, rowContext))
                        append(" · ")
                        append(
                            if (entry.downloaded) stringResource(R.string.local_models_downloaded)
                            else stringResource(R.string.local_models_not_downloaded)
                        )
                        if (entry.active) {
                            append(" · ")
                            append(stringResource(R.string.local_models_active))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = themeTextGrey(),
                )
                // 完整性两态小字
                if (entry.downloaded) {
                    Spacer(Modifier.height(SpacingTokens.xxs))
                    Text(
                        text = if (entry.verified) stringResource(R.string.local_models_verified_ok)
                        else stringResource(R.string.local_models_verified_basic),
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey(),
                    )
                }
                Spacer(Modifier.height(SpacingTokens.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                    if (!entry.downloaded) {
                        Button(
                            onClick = onDownload,
                            enabled = online && !working,
                            shape = ShapeTokens.smallShape,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        ) {
                            Text(stringResource(R.string.local_models_download))
                        }
                    } else {
                        if (!entry.active) {
                            OutlinedButton(
                                onClick = onSetActive,
                                enabled = !working,
                                shape = ShapeTokens.smallShape,
                            ) {
                                Text(stringResource(R.string.local_models_set_active))
                            }
                        }
                        // 删除：在用模型置灰并提示先切换
                        val canDelete = !entry.active
                        OutlinedButton(
                            onClick = { if (canDelete) onRequestDelete() },
                            enabled = canDelete && !working,
                            shape = ShapeTokens.smallShape,
                        ) {
                            Text(stringResource(R.string.local_models_delete))
                        }
                    }
                }
                if (entry.active) {
                    Spacer(Modifier.height(SpacingTokens.xs))
                    Text(
                        text = stringResource(R.string.local_models_delete_in_use),
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey(),
                    )
                }
            }
        }
    }
}
