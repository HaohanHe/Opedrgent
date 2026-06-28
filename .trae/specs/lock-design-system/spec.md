# 锁定设计系统规范 Spec

## Why
当前项目已有 Color.kt / Type.kt / Theme.kt 的底子，但 `OpedrgentTheme` 默认开启 `dynamicColor`，导致 Android 12+ 上精心调制的品牌色被系统壁纸色覆盖；同时缺少统一的 Shape、Spacing、Elevation token，各屏幕自行硬编码 6/11/12/14/16dp 圆角和 6/8/10/12/14/16dp 间距，视觉支离破碎。

## What Changes
- **默认关闭动态取色**，把品牌色系统重新锁死；设置中保留可选开关。
- 新增 `Shape.kt` / `Spacing.kt` token 文件，统一圆角与间距体系。
- 把 `Color.kt` 中业务语义颜色收敛进 `MaterialTheme.colorScheme` 的扩展槽或本地 `CompositionLocalProvider`，减少屏幕层直接引用裸色值。
- 补齐所有 token 的深色变体，并新增 `@Preview` 基础模板，让 UI 迭代可在 IDE 内完成。
- **不改动**现有组件行为，只做 token 层统一；硬编码替换在后续 spec 中完成。

## Impact
- Affected code: `ui/theme/Color.kt`, `ui/theme/Type.kt`, `ui/theme/Theme.kt`, 新建 `ui/theme/Shape.kt`、`ui/theme/Spacing.kt`。
- Affected screens: 所有后续依赖这些 token 的屏幕与组件。

## ADDED Requirements
### Requirement: 品牌色默认生效
The system SHALL keep the Opedrgent brand palette as the default color scheme on all API levels.

#### Scenario: 冷启动（Android 13+）
- **WHEN** 用户在 Android 13 设备上首次打开 App
- **THEN** 主题使用 `LightColorScheme`/`DarkColorScheme` 中定义的品牌色，而非系统壁纸动态色

### Requirement: 统一间距/圆角 token
The system SHALL provide named spacing and shape tokens for use across screens.

#### Scenario: 设计师/开发者使用 token
- **WHEN** 开发者需要 16dp 卡片内边距
- **THEN** 使用 `SpacingTokens.large` 而不是裸写 `16.dp`

## MODIFIED Requirements
### Requirement: 动态主题开关
The system SHALL expose a user-toggleable dynamic-color setting that defaults to OFF.

## REMOVED Requirements
### Requirement: 默认动态取色
**Reason**: 动态色覆盖品牌色，导致产品辨识度下降。
**Migration**: 保留代码逻辑，仅把默认值改为 false，并在设置页提供开关。
