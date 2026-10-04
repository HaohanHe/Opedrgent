package top.hsyscn.opedrgent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.components.isAtLeastMediumWidth

/**
 * 首次使用引导。
 *
 * 设计原则：
 * - 价值优先：30 秒内讲清核心能力
 * - 可跳过：右上角始终提供跳过入口
 * - 渐进式：4 页轻量引导，每页一个主题
 * - 无阻塞：完成引导前不要求登录或权限
 * - 流畅体验：支持滑动手势、丰富动画、响应式过渡
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var isFinishing by rememberSaveable { mutableStateOf(false) }
    var isAnimating by remember { mutableStateOf(false) }

    val pages = listOf(
        OnboardingPage(
            icon = Icons.Default.Mic,
            title = stringResource(R.string.onboarding_page_record_title),
            subtitle = stringResource(R.string.onboarding_page_record_subtitle),
            gradientStart = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            gradientEnd = Color.Transparent,
        ),
        OnboardingPage(
            icon = Icons.Default.AutoAwesome,
            title = stringResource(R.string.onboarding_page_insight_title),
            subtitle = stringResource(R.string.onboarding_page_insight_subtitle),
            gradientStart = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
            gradientEnd = Color.Transparent,
        ),
        OnboardingPage(
            icon = Icons.Default.Visibility,
            title = stringResource(R.string.onboarding_page_lenses_title),
            subtitle = stringResource(R.string.onboarding_page_lenses_subtitle),
            gradientStart = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
            gradientEnd = Color.Transparent,
        ),
        OnboardingPage(
            icon = Icons.Default.CheckCircle,
            title = stringResource(R.string.onboarding_page_action_title),
            subtitle = stringResource(R.string.onboarding_page_action_subtitle),
            gradientStart = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            gradientEnd = Color.Transparent,
            privacyFooter = true,
        ),
    )

    val onNext = {
        if (page < pages.lastIndex) {
            page++
        }
    }

    val onPrev = {
        if (page > 0) {
            page--
        }
    }

    val scope = rememberCoroutineScope()
    val onComplete = {
        isFinishing = true
        scope.launch {
            delay(300)
            onFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(isFinishing, isAnimating) {
                if (isFinishing || isAnimating) return@pointerInput
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { },
                    onDragEnd = {
                        val screenWidth = size.width.toFloat()
                        if (totalDrag < -screenWidth * 0.2f) {
                            onNext()
                        } else if (totalDrag > screenWidth * 0.2f) {
                            onPrev()
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f },
                ) { _, dragAmount ->
                    totalDrag += dragAmount
                }
            },
    ) {
        // 背景渐变层
        AnimatedContent(
            targetState = pages[page].gradientStart,
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
            },
            label = "bg_gradient",
        ) { color ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(color, Color.Transparent),
                            startY = 0f,
                            endY = Float.POSITIVE_INFINITY,
                        ),
                    ),
            )
        }

        val onboardingMaxWidth = if (isAtLeastMediumWidth()) SizeTokens.onboardingMaxWidth else Dp.Unspecified
        Column(
            modifier = Modifier
                .then(
                    if (onboardingMaxWidth != Dp.Unspecified) {
                        Modifier.fillMaxHeight().widthIn(max = onboardingMaxWidth)
                    } else {
                        Modifier.fillMaxSize()
                    }
                )
                .align(Alignment.Center)
                .padding(horizontal = SpacingTokens.lg)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部跳过按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = SpacingTokens.lg),
            ) {
                TextButton(
                    onClick = { onComplete() },
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Text(
                        stringResource(R.string.onboarding_skip),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 内容区：带动画切换
            LaunchedEffect(page) {
                isAnimating = true
                delay(500)
                isAnimating = false
            }
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(
                        initialOffsetX = { it * direction },
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    ) + fadeIn())
                        .togetherWith(slideOutHorizontally(
                            targetOffsetX = { -it * direction },
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        ) + fadeOut())
                },
                label = "onboarding_page",
            ) { currentPage ->
                OnboardingPageContent(
                    page = pages[currentPage],
                    pageIndex = currentPage,
                    totalPages = pages.size,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 指示器
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pages.indices.forEach { index ->
                    val active = index == page
                    AnimatedContent(
                        targetState = active,
                        transitionSpec = {
                            scaleIn() togetherWith scaleOut()
                        },
                        label = "indicator_$index",
                    ) { isActive ->
                        Box(
                            modifier = Modifier
                                .size(if (isActive) SizeTokens.onboardingIndicatorActive else SizeTokens.onboardingIndicatorInactive)
                                .clip(CircleShape)
                                .background(
                                    if (isActive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    }
                                ),
                        )
                    }
                    if (index < pages.lastIndex) {
                        Spacer(modifier = Modifier.width(SpacingTokens.sm))
                    }
                }
            }

            Spacer(modifier = Modifier.height(SpacingTokens.xl))

            // 底部按钮区
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 上一页
                if (page > 0) {
                    TextButton(
                        onClick = onPrev,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.onboarding_prev),
                            modifier = Modifier.size(SizeTokens.iconMd),
                        )
                        Spacer(modifier = Modifier.width(SpacingTokens.xs))
                        Text(stringResource(R.string.onboarding_prev))
                    }
                } else {
                    Spacer(modifier = Modifier.width(SizeTokens.onboardingButtonPlaceholderWidth))
                }

                // 主按钮
                val isLastPage = page == pages.lastIndex
                Button(
                    onClick = {
                        if (isLastPage) {
                            onComplete()
                        } else {
                            onNext()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = CircleShape,
                ) {
                    AnimatedContent(
                        targetState = isLastPage,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "button_text",
                    ) { last ->
                        if (last) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.onboarding_get_started))
                                Spacer(modifier = Modifier.width(SpacingTokens.xs))
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(SizeTokens.iconMd),
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.onboarding_next))
                                Spacer(modifier = Modifier.width(SpacingTokens.xs))
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(SizeTokens.iconMd),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(SpacingTokens.lg))
        }
    }
}

/**
 * 单页引导内容，带入场动画
 */
@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    pageIndex: Int,
    totalPages: Int,
) {
    var showTitle by remember { mutableStateOf(false) }
    var showSubtitle by remember { mutableStateOf(false) }
    val iconScale = remember { Animatable(0.8f) }
    val iconAlpha = remember { Animatable(0f) }

    LaunchedEffect(pageIndex) {
        showTitle = false
        showSubtitle = false
        iconScale.snapTo(0.8f)
        iconAlpha.snapTo(0f)

        launch {
            iconScale.animateTo(
                1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            )
        }
        launch {
            delay(100)
            iconAlpha.animateTo(1f, animationSpec = tween(300))
        }

        delay(150)
        showTitle = true
        delay(100)
        showSubtitle = true
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 图标容器：弹跳入场
        Box(
            modifier = Modifier
                .size(SizeTokens.onboardingIconContainerSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .scale(iconScale.value)
                .alpha(iconAlpha.value),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                modifier = Modifier.size(SizeTokens.onboardingIconSize),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(SpacingTokens.xl))

        // 标题：从下方滑入
        AnimatedVisibility(
            visible = showTitle,
            enter = slideInVertically(initialOffsetY = { 20 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -20 }) + fadeOut(),
        ) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(SpacingTokens.md))

        // 副标题：延迟滑入
        AnimatedVisibility(
            visible = showSubtitle,
            enter = slideInVertically(initialOffsetY = { 16 }) + fadeIn(animationSpec = tween(400)),
            exit = slideOutVertically(targetOffsetY = { -16 }) + fadeOut(),
        ) {
            Text(
                text = page.subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // 隐私透明小字与隐私政策入口（仅末屏展示）
        if (page.privacyFooter) {
            PrivacyFooter()
        }

        // 页码提示（第 X / Y 页）
        Spacer(modifier = Modifier.height(SpacingTokens.lg))
        Text(
            text = "${pageIndex + 1} / $totalPages",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val gradientStart: Color,
    val gradientEnd: Color,
    val privacyFooter: Boolean = false,
)

/**
 * 末屏隐私透明小字与隐私政策入口：
 * 数据默认仅存本机；云端默认关闭，仅在用户主动配置使用时才发送。
 * 点击用系统可处理的视图打开隐私政策；无机能打开时静默兜底，不崩溃。
 */
@Composable
private fun PrivacyFooter() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(SpacingTokens.md))
        Text(
            text = stringResource(R.string.onboarding_privacy_on_device),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(SpacingTokens.xs))
        Text(
            text = stringResource(R.string.onboarding_privacy_cloud_optin),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = { openPrivacyPolicy(context) }) {
            Text(
                text = stringResource(R.string.onboarding_privacy_policy_link),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun openPrivacyPolicy(context: Context) {
    val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(context.getString(R.string.privacy_policy_url)),
    )
    val canOpen = runCatching {
        intent.resolveActivity(context.packageManager) != null
    }.getOrDefault(false)
    if (canOpen) {
        runCatching { context.startActivity(intent) }
    }
}
