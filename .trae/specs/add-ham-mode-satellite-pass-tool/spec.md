# Ham 模式与卫星过境工具 Spec

## Why
为“2026 国际空间通信挑战赛 / 匠造空间通信”赛项，在 Opedrgent 现有庞大架构上以最轻量方式叠加空间通信能力：仅在设置中开启 Ham 模式后暴露一个卫星过境工具，使模型能够基于用户位置推荐可通联的业余卫星并给出过境窗口、频率与调制方式，而不改动现有 AI 助手主体。

## What Changes
- 在 `ApiSettings` 中新增 Ham 模式开关 `hamModeEnabled` 及持久化读写方法。
- 在 `SettingsScreen` 的“功能”分组中新增“Ham 模式（业余卫星）”开关。
- 新增 `SatellitePassTool` 工具类，提供 `satellite_pass` 函数调用能力。
- 在 `ToolExecutor` 中条件注册 `SatellitePassTool`：仅当 `ApiSettings.isHamModeEnabled()` 为 true 时注册。
- 新增本地业余卫星基础数据库（JSON 或代码内嵌）：卫星名称、NORAD ID、常用频率、调制方式、最低仰角/备注。
- 工具内部支持两种调用方式：
  - `list`：列出当前 Ham 模式支持跟踪的业余卫星及其基本信息。
  - `passes`：给定卫星 NORAD ID（或名称）和时长（默认 24 小时），基于用户最近一次缓存位置计算未来过境窗口（起始、最大仰角、结束时间）。
- 星历（TLE）从公开 Celestrak（`https://celestrak.org/NORAD/elements/amateur.txt`）拉取并本地缓存，24 小时刷新；网络失败时使用缓存或返回带提示的错误。
- 工具输出结构化文本给模型，模型自行决定是否调用 `run_calendar` 等已有工具为用户创建提醒。

## Impact
- Affected specs: 设置模块、工具注册表、位置缓存、系统提示注入。
- Affected code:
  - `settings/ApiSettings.kt`
  - `ui/SettingsScreen.kt`
  - `tools/SatellitePassTool.kt`（新增）
  - `network/ToolExecutor.kt`
  - `utils/PromptBlocks.kt` 或 `ui/MainViewModel.kt`（系统提示中可选注入 Ham 模式标志）
  - `assets/` 或 `res/raw/`（业余卫星基础信息 JSON）

## ADDED Requirements

### Requirement: Ham 模式开关
The system SHALL provide a toggle in SettingsScreen to enable/disable “Ham 模式（业余卫星）”. The default SHALL be disabled.

#### Scenario: 用户开启 Ham 模式
- **WHEN** 用户在设置页打开 Ham 模式开关
- **THEN** `ApiSettings.saveHamModeEnabled(true)` 持久化，且 `ToolExecutor` 随后注册 `satellite_pass` 工具供 LLM 使用

#### Scenario: 用户关闭 Ham 模式
- **WHEN** 用户关闭 Ham 模式开关
- **THEN** `ApiSettings.saveHamModeEnabled(false)` 持久化，且应用重启后 `satellite_pass` 不再注册

### Requirement: 卫星过境工具
The system SHALL expose a tool named `satellite_pass` only when Ham mode is enabled. The tool SHALL accept `action` ("list" | "passes"), `satellite` (optional name/NORAD ID), and `hours` (optional integer, default 24).

#### Scenario: 列星
- **WHEN** 模型调用 `satellite_pass` 且 `action=list`
- **THEN** 工具返回内置业余卫星列表，包含名称、NORAD ID、频率、调制方式、简要说明

#### Scenario: 算过境
- **WHEN** 模型调用 `satellite_pass` 且 `action=passes` 并指定卫星与时长
- **THEN** 工具基于缓存的用户位置和 Celestrak TLE 计算未来 `hours` 小时内的过境窗口，返回每个窗口的开始时间、最大仰角、结束时间、方向；若未获取位置则返回错误提示

#### Scenario: 离线/无网络
- **WHEN** 网络不可用且无缓存 TLE
- **THEN** 工具返回错误，提示用户检查网络；若有缓存 TLE 则继续使用缓存并标注数据时间

### Requirement: TLE 缓存
The system SHALL cache fetched TLE data locally and refresh it only if older than 24 hours or on explicit request.

#### Scenario: TLE 缓存命中
- **WHEN** 工具需要 TLE 且本地缓存未过期
- **THEN** 不访问网络，直接使用缓存计算

## MODIFIED Requirements

### Requirement: 工具注册条件化
`ToolExecutor` 在初始化工具注册表时，对 `SatellitePassTool` 的注册 SHALL 以 `apiSettings.isHamModeEnabled()` 为条件。

#### Scenario: 注册时 Ham 模式关闭
- **WHEN** `ToolExecutor` 初始化且 Ham 模式关闭
- **THEN** `satellite_pass` 不进入工具列表，LLM 不会看到该工具

## REMOVED Requirements
无。
