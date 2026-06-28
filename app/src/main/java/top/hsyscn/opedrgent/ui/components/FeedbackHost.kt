package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 统一的反馈宿主：包裹 [content] 并提供一个 [SnackbarHost]，
 * 使任意子组件都能通过 [LocalFeedbackController] 弹出 Snackbar，
 * 而无需各自持有 SnackbarHostState。
 *
 * 对于已自带 Scaffold+SnackbarHost 的屏幕（如 AppRoot），可直接用
 * 现有的 SnackbarHostState 构造 [FeedbackController] 并通过
 * [CompositionLocalProvider] 提供，无需嵌套本组件的 Scaffold。
 */
@Composable
fun FeedbackHost(content: @Composable () -> Unit) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val controller = remember(snackbarHostState, scope) {
        FeedbackController(snackbarHostState, scope)
    }
    CompositionLocalProvider(LocalFeedbackController provides controller) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            // padding 交由内部各屏幕的 Scaffold 自行处理；此处不额外施加
            content()
        }
    }
}

/**
 * 访问当前反馈控制器的 CompositionLocal。
 * 未提供时读取会抛出异常——使用前请确保上层已通过 [FeedbackHost] 或
 * [CompositionLocalProvider] 提供控制器。
 */
val LocalFeedbackController = compositionLocalOf<FeedbackController> {
    error("No FeedbackController provided. Wrap content in FeedbackHost or provide one via CompositionLocalProvider.")
}

/**
 * 反馈控制器：在给定 [CoroutineScope] 内向 [snackbarHostState] 推送 Snackbar。
 *
 * 支持可选的动作按钮：当 [actionLabel] 非空且用户点击该动作时，触发 [action]。
 */
class FeedbackController(
    private val snackbarHostState: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    fun showFeedback(
        message: String,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
    ) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
            )
            if (result == SnackbarResult.ActionPerformed) {
                action?.invoke()
            }
        }
    }
}
