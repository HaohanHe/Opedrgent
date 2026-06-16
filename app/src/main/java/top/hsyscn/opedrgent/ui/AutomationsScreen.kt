@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.automation.Automation
import top.hsyscn.opedrgent.automation.AutomationKind
import top.hsyscn.opedrgent.automation.AutomationStore

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
                title = { Text("自动化/心跳") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "back")
                    }
                },
                actions = {
                    IconButton(onClick = { createOpen = true }) {
                        Icon(Icons.Default.Add, contentDescription = "add")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(automations, key = { it.id }) { a ->
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                    onClick = { editing = a; editOpen = true },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = a.name, fontWeight = FontWeight.SemiBold)
                            Text(text = "${a.kind.name} · ${a.intervalMinutes} 分钟", modifier = Modifier.padding(top = 4.dp))
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
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("新建自动化") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = interval, onValueChange = { interval = it }, label = { Text("周期分钟数（>=15）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { kind = AutomationKind.HEARTBEAT_NOTES.name }, modifier = Modifier.weight(1f)) { Text("心跳整理") }
                    Button(onClick = { kind = AutomationKind.RUN_PROMPT.name }, modifier = Modifier.weight(1f)) { Text("定时 Prompt") }
                }
                if (kind == AutomationKind.RUN_PROMPT.name) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("Prompt") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                    )
                } else {
                    Text("心跳整理：周期性生成/更新 Session Notes。")
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
            }) { Text("保存") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDelete) { Text("删除") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        title = { Text("编辑自动化") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = interval, onValueChange = { interval = it }, label = { Text("周期分钟数（>=15）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (automation.kind == AutomationKind.RUN_PROMPT) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("Prompt") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                    )
                } else {
                    Text("类型：心跳整理")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "启用", modifier = Modifier.weight(1f))
                    Switch(
                        checked = automation.enabled,
                        onCheckedChange = { onSave(automation.copy(enabled = it, updatedAt = System.currentTimeMillis())) },
                    )
                }
            }
        },
    )
}
