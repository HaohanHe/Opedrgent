package top.hsyscn.opedrgent.ui

import top.hsyscn.opedrgent.ui.theme.InterviewPurple
import top.hsyscn.opedrgent.ui.theme.InterviewDarkBg
import top.hsyscn.opedrgent.ui.theme.WarningColor
import top.hsyscn.opedrgent.ui.theme.SuccessGreen
import top.hsyscn.opedrgent.ui.theme.AccentOrange
import top.hsyscn.opedrgent.ui.theme.customColors
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

data class OpenSourceProject(
    val name: String,
    val description: String,
    val license: String,
    val url: String,
)

private val projects = listOf(
    // ── 语音与音频 ──
    OpenSourceProject(
        name = "Sherpa-ONNX",
        description = "跨平台本地语音处理工具包，支持 ASR/TTS/说话人识别/VAD",
        license = "Apache License 2.0",
        url = "https://github.com/k2-fsa/sherpa-onnx",
    ),
    OpenSourceProject(
        name = "MiMo ASR",
        description = "小米高性能语音识别模型",
        license = "MIT License",
        url = "https://github.com/xiaomi/mimo",
    ),
    // ── 视觉与 OCR ──
    OpenSourceProject(
        name = "PaddleOCR",
        description = "百度开源 OCR 引擎，支持多语言文字识别",
        license = "Apache License 2.0",
        url = "https://github.com/PaddlePaddle/PaddleOCR",
    ),
    // ── 大语言模型推理 ──
    OpenSourceProject(
        name = "LiteRT-LM",
        description = "Google 端侧大模型推理框架",
        license = "Apache License 2.0",
        url = "https://github.com/google-ai-edge/LiteRT-LM",
    ),
    OpenSourceProject(
        name = "llama.cpp",
        description = "Georgi Gerganov 的 LLM C/C++ 推理引擎",
        license = "MIT License",
        url = "https://github.com/ggml-org/llama.cpp",
    ),
    OpenSourceProject(
        name = "MediaPipe",
        description = "Google 跨平台机器学习解决方案",
        license = "Apache License 2.0",
        url = "https://github.com/google-ai-edge/mediapipe",
    ),
    // ── 搜索引擎 ──
    OpenSourceProject(
        name = "Meilisearch",
        description = "极速开源搜索引擎，支持混合搜索与容错",
        license = "MIT License",
        url = "https://github.com/meilisearch/meilisearch",
    ),
    OpenSourceProject(
        name = "SearXNG",
        description = "隐私保护的元搜索引擎，聚合多源搜索结果",
        license = "AGPL-3.0 License",
        url = "https://github.com/searxng/searxng",
    ),
    OpenSourceProject(
        name = "Qdrant",
        description = "Rust 编写的高性能向量相似度搜索引擎",
        license = "Apache License 2.0",
        url = "https://github.com/qdrant/qdrant",
    ),
    // ── AI Agent 框架 ──
    OpenSourceProject(
        name = "Koog",
        description = "JetBrains Kotlin AI Agent 框架，图工作流 + MCP/ACP",
        license = "Apache License 2.0",
        url = "https://github.com/JetBrains/koog",
    ),
    OpenSourceProject(
        name = "Hermes Agent",
        description = "Nous Research 自改进 AI Agent，内置学习循环与技能系统",
        license = "MIT License",
        url = "https://github.com/NousResearch/hermes-agent",
    ),
    OpenSourceProject(
        name = "GELab-Zero",
        description = "阶跃星辰全开源 GUI Agent，本地推理 + 4B 端侧模型",
        license = "MIT License",
        url = "https://github.com/stepfun-ai/gelab-zero",
    ),
    OpenSourceProject(
        name = "Kilo Code",
        description = "全平台 AI 编程助手，支持 500+ 模型",
        license = "MIT License",
        url = "https://github.com/kilo-code/kilo-code",
    ),
    OpenSourceProject(
        name = "MiMo Code",
        description = "小米开源 AI 编程 Agent，跨会话记忆 + 多 Agent 协作",
        license = "MIT License",
        url = "https://github.com/xiaomi/mimo-code",
    ),
    OpenSourceProject(
        name = "ML Intern",
        description = "Hugging Face 自主 ML 研究员，自动化机器学习全流程",
        license = "Apache License 2.0",
        url = "https://github.com/huggingface/ml-intern",
    ),
    // ── Android / Kotlin ──
    OpenSourceProject(
        name = "Jetpack Compose",
        description = "Google 声明式 Android UI 工具包",
        license = "Apache License 2.0",
        url = "https://github.com/androidx/androidx",
    ),
    OpenSourceProject(
        name = "Kotlin Coroutines",
        description = "JetBrains Kotlin 协程库",
        license = "Apache License 2.0",
        url = "https://github.com/Kotlin/kotlinx.coroutines",
    ),
    OpenSourceProject(
        name = "Room Database",
        description = "Google Android 本地数据库 ORM",
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/androidx/releases/room",
    ),
    OpenSourceProject(
        name = "DataStore",
        description = "Google Android 数据存储方案",
        license = "Apache License 2.0",
        url = "https://developer.android.com/topic/libraries/architecture/datastore",
    ),
    // ── 富文本编辑器 ──
    OpenSourceProject(
        name = "Notally",
        description = "极简 Android 笔记应用，富文本编辑器设计灵感来源",
        license = "GPL-3.0 License",
        url = "https://github.com/OmGodse/Notally",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_open_source), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
                },
            )
        },
        containerColor = themeBgGray(),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        ) {
            item {
                Text(
                    text = stringResource(R.string.about_open_source_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = themeTextGrey(),
                )
                Spacer(modifier = Modifier.height(SpacingTokens.sm))
            }

            items(projects, key = { it.name }) { project ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ShapeTokens.mediumShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .padding(SpacingTokens.md)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = project.name,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleSmall,
                                color = themeTextDark(),
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = project.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = themeTextGrey(),
                            )
                            Spacer(modifier = Modifier.height(SpacingTokens.xs))
                            Text(
                                text = project.license,
                                style = MaterialTheme.typography.labelSmall,
                                color = themeTextGrey(),
                            )
                        }
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = stringResource(R.string.cd_open_link),
                            modifier = Modifier
                                .size(18.dp)
                                .clickable {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(project.url)))
                                },
                            tint = AccentBlue,
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(SpacingTokens.lg))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(SpacingTokens.sm))
                Text(
                    text = "共 ${projects.size} 个开源项目",
                    style = MaterialTheme.typography.bodySmall,
                    color = themeTextGrey(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
