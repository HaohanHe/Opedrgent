package top.hsyscn.opedrgent.ui.components

import android.content.res.Configuration
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 不依赖 Activity/WindowMetrics 的窗口尺寸类别封装。
 *
 * 直接基于 [LocalConfiguration] 的 `screenWidthDp` / `screenHeightDp` 计算断点，
 * 彻底绕开 `calculateWindowSizeClass(activity)` 在 `android:configChanges` 下可能缓存旧值的问题，
 * 确保旋转/分屏/无极窗口尺寸变化时立即触发重组。
 */
@Immutable
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
data class WindowSizeInfo(
    val widthSizeClass: WindowWidthSizeClass,
    val heightSizeClass: WindowHeightSizeClass,
)

/**
 * 获取当前窗口尺寸类别（Compact / Medium / Expanded）。
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberWindowSizeInfo(): WindowSizeInfo {
    val configuration = LocalConfiguration.current
    val widthClass = when {
        configuration.screenWidthDp < 600 -> WindowWidthSizeClass.Compact
        configuration.screenWidthDp < 840 -> WindowWidthSizeClass.Medium
        else -> WindowWidthSizeClass.Expanded
    }
    val heightClass = when {
        configuration.screenHeightDp < 480 -> WindowHeightSizeClass.Compact
        configuration.screenHeightDp < 900 -> WindowHeightSizeClass.Medium
        else -> WindowHeightSizeClass.Expanded
    }
    return WindowSizeInfo(widthClass, heightClass)
}

/**
 * 当前是否处于横屏。
 */
@Composable
fun isLandscape(): Boolean {
    return LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
}

/**
 * 当前窗口宽度是否为 Compact（手机竖屏 / 小窗）。
 */
@Composable
fun isCompactWidth(): Boolean {
    return rememberWindowSizeInfo().widthSizeClass == WindowWidthSizeClass.Compact
}

/**
 * 当前窗口宽度是否为 Medium（小平板 / 折叠屏半开 / 大手机横屏）。
 */
@Composable
fun isMediumWidth(): Boolean {
    return rememberWindowSizeInfo().widthSizeClass == WindowWidthSizeClass.Medium
}

/**
 * 当前窗口宽度是否为 Expanded（平板 / 折叠屏展开 / 大屏横屏）。
 */
@Composable
fun isExpandedWidth(): Boolean {
    return rememberWindowSizeInfo().widthSizeClass == WindowWidthSizeClass.Expanded
}

/**
 * 当前窗口宽度至少为 Medium。
 */
@Composable
fun isAtLeastMediumWidth(): Boolean {
    val widthClass = rememberWindowSizeInfo().widthSizeClass
    return widthClass == WindowWidthSizeClass.Medium ||
            widthClass == WindowWidthSizeClass.Expanded
}

/**
 * 当前窗口宽度至少为 Expanded。
 */
@Composable
fun isAtLeastExpandedWidth(): Boolean {
    return rememberWindowSizeInfo().widthSizeClass == WindowWidthSizeClass.Expanded
}

/**
 * 根据窗口宽度返回不同值。
 */
@Composable
fun <T> rememberResponsiveValue(
    compact: T,
    medium: T,
    expanded: T,
): T {
    return when (rememberWindowSizeInfo().widthSizeClass) {
        WindowWidthSizeClass.Compact -> compact
        WindowWidthSizeClass.Medium -> medium
        WindowWidthSizeClass.Expanded -> expanded
        else -> compact
    }
}

/**
 * 计算当前窗口可用宽度（dp）。
 *
 * 注意：在分屏/多窗口模式下，返回的是当前应用窗口宽度，而非整个屏幕宽度。
 */
@Composable
fun rememberWindowWidthDp(): Int {
    return LocalConfiguration.current.screenWidthDp
}
