package top.hsyscn.opedrgent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

/**
 * 首次使用引导。
 *
 * 设计原则：
 * - 价值优先：30 秒内讲清核心能力
 * - 可跳过：右上角始终提供跳过入口
 * - 渐进式：4 页轻量引导，每页一个主题
 * - 无阻塞：完成引导前不要求登录或权限
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val pages = listOf(
        OnboardingPage(
            icon = Icons.Default.Spa,
            title = stringResource(R.string.onboarding_page_welcome_title),
            subtitle = stringResource(R.string.onboarding_page_welcome_subtitle),
        ),
        OnboardingPage(
            icon = Icons.AutoMirrored.Filled.Chat,
            title = stringResource(R.string.onboarding_page_chat_title),
            subtitle = stringResource(R.string.onboarding_page_chat_subtitle),
        ),
        OnboardingPage(
            icon = Icons.Default.Book,
            title = stringResource(R.string.onboarding_page_notes_title),
            subtitle = stringResource(R.string.onboarding_page_notes_subtitle),
        ),
        OnboardingPage(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.onboarding_page_offline_title),
            subtitle = stringResource(R.string.onboarding_page_offline_subtitle),
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                onClick = onFinished,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // 内容区：带动画切换
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (slideInHorizontally { it * direction } + fadeIn())
                    .togetherWith(slideOutHorizontally { -it * direction } + fadeOut())
            },
            label = "onboarding_page",
        ) { currentPage ->
            val item = pages[currentPage]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(modifier = Modifier.height(SpacingTokens.xl))

                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(SpacingTokens.md))

                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // 指示器
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.indices.forEach { index ->
                val active = index == page
                Box(
                    modifier = Modifier
                        .size(if (active) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            }
                        )
                )
                if (index < pages.lastIndex) {
                    Spacer(modifier = Modifier.width(SpacingTokens.sm))
                }
            }
        }

        Spacer(modifier = Modifier.height(SpacingTokens.xl))

        // 底部按钮
        val isLastPage = page == pages.lastIndex
        Button(
            onClick = {
                if (isLastPage) {
                    onFinished()
                } else {
                    page++
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (isLastPage) {
                    stringResource(R.string.onboarding_get_started)
                } else {
                    stringResource(R.string.onboarding_next)
                }
            )
        }

        Spacer(modifier = Modifier.height(SpacingTokens.lg))
    }
}

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
)
