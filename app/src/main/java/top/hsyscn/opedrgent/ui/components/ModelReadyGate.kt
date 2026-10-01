package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.modelreadiness.ComponentStatus
import top.hsyscn.opedrgent.modelreadiness.ModelKind
import top.hsyscn.opedrgent.modelreadiness.ModelReadinessSnapshot
import top.hsyscn.opedrgent.modelreadiness.ReadyState
import top.hsyscn.opedrgent.ui.theme.ElevationTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

/**
 * 端侧模型「就绪门控」与引导 UI。
 *
 * 本文件只做展示与回调：
 * - 数据全部来自冻结契约 [ComponentStatus] / [ModelReadinessSnapshot]，不自行判定业务就绪口径；
 * - 不内置任何词表，不对模型内容做语义判断；
 * - 每种状态只暴露当下可用动作，不出现僵尸按钮。
 */

/**
 * 单个模型组件的就绪/下载卡片。
 *
 * 按 [status.state] 渲染：
 * - [ReadyState.NOT_PRESENT]：说明 + 体积提示 + 「下载模型」，无网络时按钮置灰；
 * - [ReadyState.DOWNLOADING]：进度条、百分比、已下载/总量、速度 + 「暂停」「取消」；
 * - [ReadyState.FAILED]：失败原因 + 「重试」「取消」；
 * - [ReadyState.READY]：紧凑一行，附体积/完整性校验说明。
 */
@Composable
fun ModelRequiredCard(
    status: ComponentStatus,
    online: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.sm),
    ) {
        Column(
            modifier = Modifier.padding(SpacingTokens.lg),
        ) {
            // 组件身份（小字 overline），始终给出当前是哪个模型
            Text(
                text = status.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(SpacingTokens.sm))

            when (status.state) {
                ReadyState.NOT_PRESENT -> NotPresentContent(
                    status = status,
                    online = online,
                    onStart = onStart,
                )

                ReadyState.DOWNLOADING -> DownloadingContent(
                    status = status,
                    onPause = onPause,
                    onCancel = onCancel,
                )

                ReadyState.FAILED -> FailedContent(
                    status = status,
                    onRetry = onRetry,
                    onCancel = onCancel,
                )

                ReadyState.READY -> ReadyContent(status = status)
            }
        }
    }
}

@Composable
private fun NotPresentContent(
    status: ComponentStatus,
    online: Boolean,
    onStart: () -> Unit,
) {
    Text(
        text = "本地模型尚未下载。下载后可在无网络下运行，下载过程需要网络。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(SpacingTokens.xs))

    Text(
        text = "约占用 ${formatMb(status.sizeBytes)} 存储空间，请预留可用空间。",
        style = MaterialTheme.typography.bodySmall,
        color = themeTextGrey(),
    )

    Spacer(modifier = Modifier.height(SpacingTokens.md))

    Button(
        onClick = onStart,
        enabled = online,
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.mediumShape,
    ) {
        Text("下载模型")
    }

    if (!online) {
        Spacer(modifier = Modifier.height(SpacingTokens.sm))
        Text(
            text = "当前无网络，暂不能下载。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun DownloadingContent(
    status: ComponentStatus,
    onPause: () -> Unit,
    onCancel: () -> Unit,
) {
    val progress = status.progress.coerceIn(0f, 1f)
    val percent = (progress * 100).toInt().coerceIn(0, 100)

    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(SizeTokens.progressTrackHeightSm)
            .clip(ShapeTokens.smallShape),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
    )

    Spacer(modifier = Modifier.height(SpacingTokens.sm))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "${formatMb(status.downloadedBytes)} / ${formatMb(status.sizeBytes)} · ${formatMb(status.speedBytesPerSec)}/s",
            style = MaterialTheme.typography.labelSmall,
            color = themeTextGrey(),
        )
    }

    Spacer(modifier = Modifier.height(SpacingTokens.md))

    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f),
            shape = ShapeTokens.mediumShape,
        ) {
            Text("取消")
        }
        Spacer(modifier = Modifier.width(SpacingTokens.sm))
        Button(
            onClick = onPause,
            modifier = Modifier.weight(1f),
            shape = ShapeTokens.mediumShape,
        ) {
            Text("暂停")
        }
    }
}

@Composable
private fun FailedContent(
    status: ComponentStatus,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(SizeTokens.iconMd),
        )
        Spacer(modifier = Modifier.width(SpacingTokens.sm))
        Text(
            text = "下载失败",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(modifier = Modifier.height(SpacingTokens.xs))

    Text(
        text = status.error ?: "下载过程出现未知错误。",
        style = MaterialTheme.typography.bodySmall,
        color = themeTextGrey(),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )

    Spacer(modifier = Modifier.height(SpacingTokens.md))

    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f),
            shape = ShapeTokens.mediumShape,
        ) {
            Text("取消")
        }
        Spacer(modifier = Modifier.width(SpacingTokens.sm))
        Button(
            onClick = onRetry,
            modifier = Modifier.weight(1f),
            shape = ShapeTokens.mediumShape,
        ) {
            Text("重试")
        }
    }
}

@Composable
private fun ReadyContent(status: ComponentStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(SizeTokens.iconMd),
        )
        Spacer(modifier = Modifier.width(SpacingTokens.sm))
        Text(
            text = "已就绪",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(modifier = Modifier.height(SpacingTokens.xs))

    Text(
        text = if (status.verified) "已通过完整性校验" else "已按体积校验",
        style = MaterialTheme.typography.bodySmall,
        color = themeTextGrey(),
    )
}

/**
 * 离线模型准备引导页。
 *
 * - 顶部标题与导语说明这些模型的作用与可延后处理；
 * - LLM、ASR 各渲染一张 [ModelRequiredCard]；TTS 为系统能力，仅一行说明；
 * - 底部「稍后」延后；LLM 与 ASR 均就绪后「进入应用」可用。
 *
 * 窄屏纵向滚动；中宽及以上按 onboarding 最大宽度居中。
 */
@Composable
fun ModelSetupScreen(
    snapshot: ModelReadinessSnapshot,
    onStart: (ModelKind) -> Unit,
    onPause: (ModelKind) -> Unit,
    onResume: (ModelKind) -> Unit,
    onCancel: (ModelKind) -> Unit,
    onRetry: (ModelKind) -> Unit,
    onDefer: () -> Unit,
    onDone: () -> Unit,
) {
    // 核心可下载组件：LLM 与 ASR。TTS 为系统能力，不纳入就绪门槛。
    val coreReady = snapshot.llm.state == ReadyState.READY &&
            snapshot.asr.state == ReadyState.READY

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val maxWidth = if (isAtLeastMediumWidth()) SizeTokens.onboardingMaxWidth else null
        Column(
            modifier = Modifier
                .then(if (maxWidth != null) Modifier.widthIn(max = maxWidth) else Modifier.fillMaxWidth())
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SizeTokens.screenHorizontalPadding)
                .navigationBarsPadding(),
        ) {
            Spacer(modifier = Modifier.height(SpacingTokens.xxl))

            Text(
                text = "离线模型准备",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(SpacingTokens.md))

            Text(
                text = "这些模型让聊天、语音与批判镜可在无网络下运行。可现在下载，也可稍后自行处理。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(SpacingTokens.xl))

            // LLM
            ModelRequiredCard(
                status = snapshot.llm,
                online = snapshot.online,
                onStart = { onStart(ModelKind.LLM) },
                onPause = { onPause(ModelKind.LLM) },
                onResume = { onResume(ModelKind.LLM) },
                onCancel = { onCancel(ModelKind.LLM) },
                onRetry = { onRetry(ModelKind.LLM) },
            )

            Spacer(modifier = Modifier.height(SpacingTokens.md))

            // ASR
            ModelRequiredCard(
                status = snapshot.asr,
                online = snapshot.online,
                onStart = { onStart(ModelKind.ASR) },
                onPause = { onPause(ModelKind.ASR) },
                onResume = { onResume(ModelKind.ASR) },
                onCancel = { onCancel(ModelKind.ASR) },
                onRetry = { onRetry(ModelKind.ASR) },
            )

            Spacer(modifier = Modifier.height(SpacingTokens.md))

            // TTS：系统能力，无需下载
            TtsRow(state = snapshot.tts.state)

            Spacer(modifier = Modifier.height(SpacingTokens.xl))

            // 底部动作
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onDefer,
                    modifier = Modifier.weight(1f),
                    shape = ShapeTokens.mediumShape,
                ) {
                    Text("稍后")
                }
                Spacer(modifier = Modifier.width(SpacingTokens.sm))
                Button(
                    onClick = onDone,
                    enabled = coreReady,
                    modifier = Modifier.weight(1f),
                    shape = ShapeTokens.mediumShape,
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text("进入应用")
                }
            }

            Spacer(modifier = Modifier.height(SpacingTokens.lg))
        }
    }
}

@Composable
private fun TtsRow(state: ReadyState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.sm),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.lg)) {
            Text(
                text = "语音合成",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(SpacingTokens.xs))
            Text(
                text = "系统语音合成，无需下载。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state != ReadyState.READY) {
                Spacer(modifier = Modifier.height(SpacingTokens.xs))
                Text(
                    text = "当前未检测到可用的系统语音合成，可在系统设置中开启后使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = themeTextGrey(),
                )
            }
        }
    }
}

/** 字节数 -> 可读 MB 文案。仅用于展示，不改变任何状态。 */
private fun formatMb(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(java.util.Locale.US, "%.1f MB", mb)
}
