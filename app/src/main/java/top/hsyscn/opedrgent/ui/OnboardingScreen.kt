package top.hsyscn.opedrgent.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

/**
 * 首次使用引导。
 *
 * 设计原则：
 * - 价值优先：30 秒内讲清核心能力
 * - 可跳过：右上角始终提供跳过入口
 * - 渐进式：5 页轻量引导，每页一个主题
 * - 无阻塞：完成引导前不要求登录或权限
 * - 流畅体验：支持滑动手势、丰富动画、响应式过渡
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var isFinishing by remember { mutableStateOf(false) }

    val pages = listOf(
        OnboardingPage(
            icon = Icons.Default.Spa,
            title = stringResource(R.string.onboarding_page_welcome_title),
            subtitle = stringResource(R.string.onboarding_page_welcome_subtitle),
            gradientStart = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            gradientEnd = Color.Transparent,
        ),
        OnboardingPage(
            icon = Icons.AutoMirrored.Filled.Chat,
            title = stringResource(R.string.onboarding_page_chat_title),
            subtitle = stringResource(R.string.onboarding_page_chat_subtitle),
            gradientStart = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
            gradientEnd = Color.Transparent,
        ),
        OnboardingPage(
            icon = Icons.Default.Book,
            title = stringResource(R.string.onboarding_page_notes_title),
            subtitle = stringResource(R.string.onboarding_page_notes_subtitle),
            gradientStart = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
            gradientEnd = Color.Transparent,
        ),
        OnboardingPage(
            icon = Icons.Default.AutoAwesome,
            title = stringResource(R.string.onboarding_page_sprout_title),
            subtitle = stringResource(R.string.onboarding_page_sprout_subtitle),
            gradientStart = MaterialTheme.colorScheme.successContainer.copy(alpha = 0.3f),
            gradientEnd = Color.Transparent,
        ),
        OnboardingPage(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.onboarding_page_offline_title),
            subtitle = stringResource(R.string.onboarding_page_offline_subtitle),
            gradientStart = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
            gradientEnd = Color.Transparent,
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

    val onComplete = {
        isFinishing = true
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { /* handled below */ },
                ) { _, dragAmount ->
                    if (dragAmount < -50) onNext()
                    else if (dragAmount > 50) onPrev()
                }
            },
    ) {
        // 背景渐变层
        AnimatedContent(
            targetState = pages[page].gradientStart,
            label = "bg_gradient",
        ) { color ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(color, Color.Transparent),
                            startY = 0f,
                            endY = 500f,
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = SpacingTokens.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部跳过按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = SpacingTokens.lg),
            ) {
                TextButton(
                    onClick = onComplete,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    colors = androidx.compose.material3.TextButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(
                        stringResource(R.string.onboarding_skip),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 内容区：带动画切换
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(
                        initialOffset = { it * direction },
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    ) + fadeIn())
                        .togetherWith(slideOutHorizontally(
                            targetOffset = { -it * direction },
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
                                .size(if (isActive) 24.dp else 8.dp)
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
                            contentDescription = "上一页",
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.onboarding_prev))
                    }
                } else {
                    Spacer(modifier = Modifier.width(80.dp))
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
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.onboarding_next))
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 图标容器：弹跳入场
        val iconScale = remember { Animatable(0.8f) }
        val iconAlpha = remember { Animatable(0f) }

        LaunchedEffect(pageIndex) {
            iconScale.snapTo(0.8f)
            iconAlpha.snapTo(0f)
            iconScale.animateTo(
                1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            )
            iconAlpha.animateTo(1f, animationSpec = tween(300))
        }

        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .scale(iconScale.value)
                .alpha(iconAlpha.value),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(SpacingTokens.xl))

        // 标题：从下方滑入
        AnimatedVisibility(
            visible = iconAlpha.value >= 0.5f,
            enter = slideInVertically(initialOffsetY = { 20 }) + fadeIn(),
            modifier = Modifier.animateItemPlacement(),
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
            visible = iconAlpha.value >= 0.8f,
            enter = slideInVertically(initialOffsetY = { 16 }) + fadeIn(animationSpec = tween(400)),
            modifier = Modifier.animateItemPlacement(),
        ) {
            Text(
                text = page.subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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
)
