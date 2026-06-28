# Tasks

- [x] Task 1: 在 Theme.kt 中把 `OpedrgentTheme` 的 `dynamicColor` 默认值改为 `false`
  - 文件: `ui/theme/Theme.kt`
  - 验证: 重新编译后，Android 12+ 默认不再使用系统壁纸动态色

- [x] Task 2: 在 SettingsScreen 中新增「动态主题」开关
  - 文件: `ui/SettingsScreen.kt`
  - 验证: 开关可读取/写入偏好，开启后动态色生效，关闭后恢复品牌色

- [x] Task 3: 新建 `ui/theme/Shape.kt`，定义统一圆角 token
  - 定义: `extraSmall(4dp)`、`small(8dp)`、`medium(12dp)`、`large(16dp)`、`extraLarge(24dp)`
  - 验证: 文件编译通过，MaterialTheme 能引用

- [x] Task 4: 新建 `ui/theme/Spacing.kt`，定义统一间距 token
  - 定义: `xxs(2dp)`、`xs(4dp)`、`sm(8dp)`、`md(12dp)`、`lg(16dp)`、`xl(24dp)`、`xxl(32dp)`
  - 验证: 文件编译通过

- [x] Task 5: 将 `Color.kt` 中的语义颜色接入 `MaterialTheme.colorScheme` 扩展
  - 文件: `ui/theme/Color.kt` / `ui/theme/Theme.kt`
  - 验证: 业务色（如 AccentOrange、SproutQuoteBg 等）可通过 `MaterialTheme.colorScheme` 或 `LocalCustomColors` 读取

- [x] Task 6: 为 Theme 组合添加 `@Preview` 示例
  - 文件: `ui/theme/ThemePreview.kt`（新建）
  - 验证: IDE 中能预览浅色/深色/品牌色板

# Task Dependencies
- Task 2 依赖 Task 1
- Task 5 依赖 Task 1
- Task 6 依赖 Task 1
