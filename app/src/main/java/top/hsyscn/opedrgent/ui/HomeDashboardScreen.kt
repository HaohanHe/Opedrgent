package top.hsyscn.opedrgent.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.note.FolderRepository
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.NoteRepository
import top.hsyscn.opedrgent.note.icon
import top.hsyscn.opedrgent.note.color
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.BgGray
import top.hsyscn.opedrgent.ui.theme.CardWhite
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import top.hsyscn.opedrgent.intelligence.BuddySystem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页仪表盘。
 *
 * 功能：
 * - 顶部问候语（早上好/下午好 + 日期）
 * - AI 助手快捷入口卡片（AI 图标 + 输入框）
 * - 今日统计卡片（新增笔记数、活跃天数、知识库文档数）
 * - 最近笔记列表（最新 5 条）
 * - 快捷操作按钮行
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeDashboardScreen(
    vm: MainViewModel,
    repository: NoteRepository,
    folderRepository: FolderRepository,
    buddy: BuddySystem = remember { BuddySystem() },
    onNewNote: () -> Unit,
    onNoteClick: (Long) -> Unit,
    onSendToChat: (Long) -> Unit = {},
    onSendWithSkill: (Long, String) -> Unit = { _, _ -> },
    onOpenSubScreen: (String) -> Unit = {},
    onNavigateToAi: () -> Unit,
    onNavigateToNotes: () -> Unit,
) {
    // AI 助手输入框状态
    var aiInputText by remember { mutableStateOf("") }

    // 最近笔记数据
    var recentNotes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var todayNoteCount by remember { mutableStateOf(0) }
    var totalNoteCount by remember { mutableStateOf(0L) }

    // 加载数据
    LaunchedEffect(Unit) {
        launch {
            recentNotes = repository.getRecentNotes(5)
        }
    }

    // 响应式监听笔记变化（从 repository 变更时自动刷新）
    LaunchedEffect(repository) {
        repository.getAllNotes().collect { notes ->
            val todayStart = getTodayStart()
            todayNoteCount = notes.count { it.createdAt >= todayStart }
            totalNoteCount = notes.size.toLong()
            recentNotes = notes.take(5)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgGray)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // ========== 1. 顶部问候语（BuddySystem 动态生成） ==========
        GreetingHeader(buddy = buddy)

        Spacer(modifier = Modifier.height(20.dp))

        // ========== 2. AI 助手快捷入口 ==========
        AiAssistantCard(
            inputText = aiInputText,
            onInputChange = { aiInputText = it },
            onSend = {
                if (aiInputText.isNotBlank()) {
                    vm.sendUserMessage(aiInputText)
                    aiInputText = ""
                    onNavigateToAi()
                }
            },
            onTap = onNavigateToAi,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ========== 3. 今日统计卡片 ==========
        StatsRow(
            todayCount = todayNoteCount,
            totalCount = totalNoteCount.toInt(),
            aiSessionCount = vm.sessionCount,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ========== 4. 快捷操作按钮行 ==========
        QuickActionsRow(
            onNewNote = onNewNote,
            onVoiceRecord = { onOpenSubScreen("meeting") },
            onImportFile = { onOpenSubScreen("import") },
            onSproutAnalysis = { onOpenSubScreen("notes") },
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ========== 5. 最近笔记列表 ==========
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "最近笔记",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark,
            )
            Text(
                text = "查看全部",
                fontSize = 14.sp,
                color = AccentBlue,
                modifier = Modifier.clickable { onNavigateToNotes() },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (recentNotes.isEmpty()) {
            EmptyRecentNotes(onNewNote = onNewNote, onNavigateToAi = onNavigateToAi)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(recentNotes, key = { it.id }) { note ->
                    RecentNoteItem(
                        note = note,
                        onClick = { onNoteClick(note.id) },
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        }
    }
}

// ==================== 子组件 ====================

/** 顶部问候语（BuddySystem 动态生成） */
@Composable
private fun GreetingHeader(buddy: BuddySystem) {
    val greeting = buddy.generateGreeting()
    val dateStr = SimpleDateFormat("M月d日 E", Locale.CHINA).format(Date())

    Column {
        Text(
            text = greeting,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = TextDark,
        )
        Text(
            text = dateStr,
            fontSize = 14.sp,
            color = TextGrey,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 根据时间返回问候语 */
@Composable
private fun rememberGreeting(): String {
    val hour = remember {
        val cal = java.util.Calendar.getInstance()
        cal.get(java.util.Calendar.HOUR_OF_DAY)
    }
    return when {
        hour < 6 -> "夜深了"
        hour < 12 -> "早上好"
        hour < 14 -> "中午好"
        hour < 18 -> "下午好"
        else -> "晚上好"
    }
}

/** 获取今天零点的时间戳 */
private fun getTodayStart(): Long {
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

/** AI 助手快捷入口卡片 */
@Composable
private fun AiAssistantCard(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onTap: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ai-pulse")
    val bounceOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -6f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bounce",
    )

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() },
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
        ) {
            // AI 图标 + 文字
            Row(verticalAlignment = Alignment.CenterVertically) {
                // AI 头像区域
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(AccentBlue),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.ChatBubble,
                        contentDescription = "AI 助手",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = "AI 智能助手",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextDark,
                    )
                    Text(
                        text = "随时问我任何问题，帮你整理思路",
                        fontSize = 13.sp,
                        color = TextGrey,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 输入框
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = BgGray),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = TextDark,
                            fontSize = 15.sp,
                        ),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            Box {
                                if (inputText.isEmpty()) {
                                    Text(
                                        text = "有什么想问的？直接打字发消息...",
                                        color = TextGrey,
                                        fontSize = 15.sp,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )

                    // 发送按钮
                    if (inputText.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(AccentBlue)
                                .clickable { onSend() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.ArrowForward,
                                contentDescription = "发送",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        Icon(
                            Icons.Default.ChatBubble,
                            contentDescription = null,
                            tint = TextGrey.copy(alpha = 0.5f),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 统计卡片行（3卡片：笔记 + 知识库 + AI对话） */
@Composable
private fun StatsRow(todayCount: Int, totalCount: Int, aiSessionCount: Int = 0) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatCard(title = "今日新增", value = "$todayCount 条", icon = Icons.Default.NoteAdd)
        StatCard(title = "知识库文档", value = "$totalCount 篇", icon = Icons.Default.AutoAwesome)
        StatCard(title = "AI 对话", value = "$aiSessionCount 次", icon = Icons.Default.ChatBubble)
    }
}

@Composable
private fun StatCard(title: String, value: String, icon: ImageVector) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.weight(1f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(AccentBlue.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = title, fontSize = 12.sp, color = TextGrey)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextDark)
        }
    }
}

/** 快捷操作按钮行 */
@Composable
private fun QuickActionsRow(
    onNewNote: () -> Unit,
    onVoiceRecord: () -> Unit,
    onImportFile: () -> Unit,
    onSproutAnalysis: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        QuickActionItem(icon = Icons.Default.Add, label = "新建笔记", onClick = onNewNote)
        QuickActionItem(icon = Icons.Default.Mic, label = "发音识别", onClick = onVoiceRecord)
        QuickActionItem(icon = Icons.Default.UploadFile, label = "导入文件", onClick = onImportFile)
        QuickActionItem(icon = Icons.Default.AutoAwesome, label = "发芽分析", onClick = onSproutAnalysis)
    }
}

@Composable
private fun QuickActionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(CardWhite)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = AccentBlue, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = label, fontSize = 11.sp, color = TextGrey)
    }
}

/** 最近笔记条目 */
@Composable
private fun RecentNoteItem(note: Note, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 类型图标
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(note.type.color().copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(note.type.icon(), contentDescription = null, tint = note.type.color(), modifier = Modifier.size(18.dp))
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = note.title.ifBlank { "无标题" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextDark,
                    maxLines = 1,
                )
                if (note.summary.isNotEmpty()) {
                    Text(
                        text = note.summary,
                        fontSize = 13.sp,
                        color = TextGrey,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            // 时间
            Text(
                text = formatNoteTime(note.updatedAt),
                fontSize = 12.sp,
                color = TextGrey,
            )
        }
    }
}

/** 空最近笔记状态（含 AI 对话引导） */
@Composable
private fun EmptyRecentNotes(onNewNote: () -> Unit, onNavigateToAi: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "还没有笔记",
            fontSize = 16.sp,
            color = TextGrey,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "点击下方快捷操作创建第一条笔记吧",
            fontSize = 13.sp,
            color = TextGrey.copy(alpha = 0.7f),
        )
        Spacer(modifier = Modifier.height(20.dp))

        // AI 对话引导卡片
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = AccentBlue.copy(alpha = 0.05f)),
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clickable { onNavigateToAi() },
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(AccentBlue.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.ChatBubble, null, tint = AccentBlue, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("试试 AI 智能助手", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextDark)
                    Text("输入想法，让 AI 帮你整理成结构化内容", fontSize = 12.sp, color = TextGrey)
                }
                Icon(Icons.Default.ArrowForward, null, tint = AccentBlue, modifier = Modifier.size(16.dp))
            }
        }
    }
}
