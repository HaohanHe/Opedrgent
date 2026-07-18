package top.hsyscn.opedrgent.ui

import top.hsyscn.opedrgent.ui.theme.InterviewPurple
import top.hsyscn.opedrgent.ui.theme.InterviewDarkBg
import top.hsyscn.opedrgent.ui.theme.WarningColor
import top.hsyscn.opedrgent.ui.theme.SuccessGreen
import top.hsyscn.opedrgent.ui.theme.AccentOrange
import top.hsyscn.opedrgent.ui.theme.customColors
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import android.content.Context
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

private fun getProjects(context: Context) = listOf(
    // ── 语音与音频 ──
    OpenSourceProject(
        name = "Sherpa-ONNX",
        description = context.getString(R.string.open_source_kua_ping_tai_ben_di_yu_yin),
        license = "Apache License 2.0",
        url = "https://github.com/k2-fsa/sherpa-onnx",
    ),
    OpenSourceProject(
        name = "MiMo ASR",
        description = context.getString(R.string.open_source_xiao_mi_gao_xing_neng_yu_yin),
        license = "MIT License",
        url = "https://github.com/xiaomi/mimo",
    ),
    // ── 视觉与 OCR ──
    OpenSourceProject(
        name = "PaddleOCR",
        description = context.getString(R.string.open_source_bai_du_kai_yuan_ocr_yin_qing),
        license = "Apache License 2.0",
        url = "https://github.com/PaddlePaddle/PaddleOCR",
    ),
    // ── 大语言模型推理 ──
    OpenSourceProject(
        name = "LiteRT-LM",
        description = context.getString(R.string.open_source_google_duan_ce_da_mo_xing_tui),
        license = "Apache License 2.0",
        url = "https://github.com/google-ai-edge/LiteRT-LM",
    ),
    OpenSourceProject(
        name = "llama.cpp",
        description = context.getString(R.string.open_source_georgi_gerganov_de_llm_c_c),
        license = "MIT License",
        url = "https://github.com/ggml-org/llama.cpp",
    ),
    OpenSourceProject(
        name = "MediaPipe",
        description = context.getString(R.string.open_source_google_kua_ping_tai_ji_qi_xue),
        license = "Apache License 2.0",
        url = "https://github.com/google-ai-edge/mediapipe",
    ),
    // ── 搜索引擎 ──
    OpenSourceProject(
        name = "Meilisearch",
        description = context.getString(R.string.open_source_ji_su_kai_yuan_sou_suo_yin),
        license = "MIT License",
        url = "https://github.com/meilisearch/meilisearch",
    ),
    OpenSourceProject(
        name = "SearXNG",
        description = context.getString(R.string.open_source_yin_si_bao_hu_de_yuan_sou_suo),
        license = "AGPL-3.0 License",
        url = "https://github.com/searxng/searxng",
    ),
    OpenSourceProject(
        name = "Qdrant",
        description = context.getString(R.string.open_source_rust_bian_xie_de_gao_xing),
        license = "Apache License 2.0",
        url = "https://github.com/qdrant/qdrant",
    ),
    // ── AI Agent 框架 ──
    OpenSourceProject(
        name = "Koog",
        description = context.getString(R.string.open_source_jetbrains_kotlin_ai_agent),
        license = "Apache License 2.0",
        url = "https://github.com/JetBrains/koog",
    ),
    OpenSourceProject(
        name = "Hermes Agent",
        description = context.getString(R.string.open_source_nous_research_zi_gai_jin_ai),
        license = "MIT License",
        url = "https://github.com/NousResearch/hermes-agent",
    ),
    OpenSourceProject(
        name = "GELab-Zero",
        description = context.getString(R.string.open_source_jie_yue_xing_chen_quan_kai),
        license = "MIT License",
        url = "https://github.com/stepfun-ai/gelab-zero",
    ),
    OpenSourceProject(
        name = "Kilo Code",
        description = context.getString(R.string.open_source_quan_ping_tai_ai_bian_cheng),
        license = "MIT License",
        url = "https://github.com/kilo-code/kilo-code",
    ),
    OpenSourceProject(
        name = "MiMo Code",
        description = context.getString(R.string.open_source_xiao_mi_kai_yuan_ai_bian),
        license = "MIT License",
        url = "https://github.com/xiaomi/mimo-code",
    ),
    OpenSourceProject(
        name = "ML Intern",
        description = context.getString(R.string.open_source_hugging_face_zi_zhu_ml_yan),
        license = "Apache License 2.0",
        url = "https://github.com/huggingface/ml-intern",
    ),
    // ── Android / Kotlin ──
    OpenSourceProject(
        name = "Jetpack Compose",
        description = context.getString(R.string.open_source_google_sheng_ming_shi_android),
        license = "Apache License 2.0",
        url = "https://github.com/androidx/androidx",
    ),
    OpenSourceProject(
        name = "Kotlin Coroutines",
        description = context.getString(R.string.open_source_jetbrains_kotlin_xie_cheng_ku),
        license = "Apache License 2.0",
        url = "https://github.com/Kotlin/kotlinx.coroutines",
    ),
    OpenSourceProject(
        name = "Room Database",
        description = context.getString(R.string.open_source_google_android_ben_di_shu_ju),
        license = "Apache License 2.0",
        url = "https://developer.android.com/jetpack/androidx/releases/room",
    ),
    OpenSourceProject(
        name = "DataStore",
        description = context.getString(R.string.open_source_google_android_shu_ju_cun_chu),
        license = "Apache License 2.0",
        url = "https://developer.android.com/topic/libraries/architecture/datastore",
    ),
    // ── 富文本编辑器 ──
    OpenSourceProject(
        name = "Notally",
        description = context.getString(R.string.open_source_ji_jian_android_bi_ji_ying),
        license = "GPL-3.0 License",
        url = "https://github.com/OmGodse/Notally",
    ),
    // ── 卫星与轨道力学 ──
    OpenSourceProject(
        name = "Look4Sat",
        description = context.getString(R.string.open_source_android_ye_yu_wei_xing_zhui),
        license = "GPL-3.0 License",
        url = "https://github.com/rt-bishop/Look4Sat",
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

            items(getProjects(context), key = { it.name }) { project ->
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
                    text = stringResource(R.string.open_source_gong_1_ge_kai_yuan_xiang_mu, getProjects(context).size),
                    style = MaterialTheme.typography.bodySmall,
                    color = themeTextGrey(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
