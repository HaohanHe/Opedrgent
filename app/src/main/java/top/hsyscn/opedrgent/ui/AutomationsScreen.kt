@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.themeTextGrey
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.automation.Automation
import top.hsyscn.opedrgent.automation.AutomationKind
import top.hsyscn.opedrgent.automation.AutomationStore
import top.hsyscn.opedrgent.ui.components.EmptyStateView
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AutomationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AutomationStore(context) }
    var automations by remember { mutableStateOf(store.list()) }
    var createOpen by rememberSaveable { mutableStateOf(false) }
    var editOpen by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Automation?>(null) }

    LaunchedEffect(Unit) {
        automations = store.list()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_automations)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { createOpen = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (automations.isEmpty()) {
                EmptyStateView(
                    icon = {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(SizeTokens.emptyStateIcon),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        )
                    },
                    title = stringResource(R.string.automation_empty_title),
                    subtitle = stringResource(R.string.automation_empty_subtitle),
                    actionLabel = stringResource(R.string.automation_empty_action),
                    onAction = { createOpen = true },
                    modifier = Modifier.padding(SpacingTokens.xxl),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                ) {
                    items(automations, key = { it.id }) { a ->
                        Card(
                            modifier = Modifier.padding(horizontal = SpacingTokens.md).fillMaxWidth(),
                            onClick = { editing = a; editOpen = true },
                        ) {
                            Row(
                                modifier = Modifier.padding(SpacingTokens.md),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = a.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = stringResource(R.string.automation_1_2_fen_zhong, a.kind.name, a.intervalMinutes),
                                        modifier = Modifier.padding(top = SpacingTokens.xs),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    // 执行状态
                                    if (a.executionCount > 0) {
                                        val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
                                        val lastRun = remember(a.lastExecutedAt, timeFmt) {
                                            if (a.lastExecutedAt > 0) timeFmt.format(Date(a.lastExecutedAt)) else context.getString(R.string.tool_state_unknown)
                                        }
                                        Text(
                                            text = stringResource(R.string.automation_yi_zhi_xing_1_ci_zui_jin_2, a.executionCount, lastRun),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = themeTextGrey(),
                                            modifier = Modifier.padding(top = SpacingTokens.xxs),
                                        )
                                    }
                                    if (a.lastError != null) {
                                        Text(
                                            text = stringResource(R.string.automation_zui_jin_cuo_wu_1, a.lastError),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = SpacingTokens.xxs),
                                        )
                                    }
                                }
                                Switch(
                                    checked = a.enabled,
                                    onCheckedChange = {
                                        store.setEnabled(a.id, it)
                                        automations = store.list()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (createOpen) {
        CreateAutomationDialog(
            onDismiss = { createOpen = false },
            onCreateHeartbeat = { name, interval ->
                store.createHeartbeat(name = name, intervalMinutes = interval, targetSessionId = null)
                automations = store.list()
                createOpen = false
            },
            onCreatePrompt = { name, interval, prompt ->
                store.createPrompt(name = name, intervalMinutes = interval, targetSessionId = null, prompt = prompt)
                automations = store.list()
                createOpen = false
            },
        )
    }

    if (editOpen) {
        val a = editing
        if (a != null) {
            EditAutomationDialog(
                automation = a,
                onDismiss = { editOpen = false; editing = null },
                onSave = { next ->
                    store.upsert(next)
                    if (next.enabled) store.setEnabled(next.id, true)
                    automations = store.list()
                    editOpen = false
                    editing = null
                },
                onDelete = {
                    store.delete(a.id)
                    automations = store.list()
                    editOpen = false
                    editing = null
                },
            )
        }
    }
}

@Composable
private fun CreateAutomationDialog(
    onDismiss: () -> Unit,
    onCreateHeartbeat: (String, Long) -> Unit,
    onCreatePrompt: (String, Long, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var interval by rememberSaveable { mutableStateOf("360") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(AutomationKind.HEARTBEAT_NOTES.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                val i = interval.trim().toLongOrNull()?.coerceAtLeast(15L) ?: 360L
                if (kind == AutomationKind.HEARTBEAT_NOTES.name) {
                    onCreateHeartbeat(name, i)
                } else {
                    onCreatePrompt(name, i, prompt)
                }
            }) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(R.string.automation_xin_jian_zi_dong_hua)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.md)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.kb_name_label)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = interval, onValueChange = { interval = it }, label = { Text(stringResource(R.string.automation_zhou_qi_fen_zhong_shu_15)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                    Button(onClick = { kind = AutomationKind.HEARTBEAT_NOTES.name }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.automation_type_heartbeat)) }
                    Button(onClick = { kind = AutomationKind.RUN_PROMPT.name }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.automation_type_prompt)) }
                }
                if (kind == AutomationKind.RUN_PROMPT.name) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text(stringResource(R.string.automation_prompt_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                    )
                } else {
                    Text(stringResource(R.string.automation_xin_tiao_zheng_li_zhou_qi))
                }
            }
        },
    )
}

@Composable
private fun EditAutomationDialog(
    automation: Automation,
    onDismiss: () -> Unit,
    onSave: (Automation) -> Unit,
    onDelete: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(automation.name) }
    var interval by rememberSaveable { mutableStateOf(automation.intervalMinutes.toString()) }
    var prompt by rememberSaveable { mutableStateOf(automation.prompt.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                val i = interval.trim().toLongOrNull()?.coerceAtLeast(15L) ?: automation.intervalMinutes
                onSave(
                    automation.copy(
                        name = name.trim().ifBlank { automation.name },
                        intervalMinutes = i,
                        prompt = if (automation.kind == AutomationKind.RUN_PROMPT) prompt.trim() else null,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
        title = { Text(stringResource(R.string.automation_bian_ji_zi_dong_hua)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.md)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.kb_name_label)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = interval, onValueChange = { interval = it }, label = { Text(stringResource(R.string.automation_zhou_qi_fen_zhong_shu_15)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (automation.kind == AutomationKind.RUN_PROMPT) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text(stringResource(R.string.automation_prompt_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                    )
                } else {
                    Text(stringResource(R.string.automation_lei_xing_xin_tiao_zheng_li))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(R.string.action_enable), modifier = Modifier.weight(1f))
                    Switch(
                        checked = automation.enabled,
                        onCheckedChange = { onSave(automation.copy(enabled = it, updatedAt = System.currentTimeMillis())) },
                    )
                }
            }
        },
    )
}
