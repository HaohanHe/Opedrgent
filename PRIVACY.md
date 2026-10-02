# Opedrgent Privacy Policy

## Health Connect Data

Opedrgent uses the Health Connect API to read your health and fitness data, including:

- **Steps** (daily step count)
- **Heart Rate** (average, min, max)
- **Calories Burned** (active and total)
- **Distance** (walking/running distance)
- **Sleep** (sleep session duration)
- **Exercise** (workout sessions)

### How We Use Your Health Data

- Health data is read **only on your device** and is sent to the AI model as context for health-related conversations.
- Health data is **never uploaded to our servers** or any third-party service.
- Health data is **not stored** persistently by Opedrgent beyond the current session context.
- Health data is **not shared** with any third party.

### Permissions

Opedrgent requests the following Health Connect permissions:

- `android.permission.health.READ_STEPS`
- `android.permission.health.READ_HEART_RATE`
- `android.permission.health.READ_TOTAL_CALORIES_BURNED`
- `android.permission.health.READ_DISTANCE`
- `android.permission.health.READ_SLEEP`
- `android.permission.health.READ_ACTIVE_CALORIES_BURNED`
- `android.permission.health.READ_EXERCISE`
- `android.permission.ACTIVITY_RECOGNITION`

You can revoke these permissions at any time through your device settings or the Health Connect app.

### Data Retention

Opedrgent does not retain health data. All health information is processed in-memory and discarded after the AI conversation context is cleared.

## On-Device Data

Opedrgent is local-first. The content you create — notes, review and critique-mirror records, audio recordings, and downloaded models — lives on this device and is not uploaded unless you explicitly opt in to a cloud endpoint (see Cloud Services below).

### Storage Locations

Your content is kept inside Opedrgent's private application sandbox:

- internal app storage (`filesDir`) for the content database, notes and note images, recordings, exported files, and on-device models;
- the app-private external directory (`getExternalFilesDir(...)`) for larger downloads such as generated images.

These directories belong to Opedrgent and are not exposed to other apps. On Android 10 and above no broad external-storage permission is required to read or write them. The only storage-related declarations are a scoped `READ_EXTERNAL_STORAGE` limited to Android 9 and below, and `READ_MEDIA_AUDIO` used when you pick an audio file yourself.

### Security

- Sensitive configuration — most notably your cloud API Keys and base URLs — is stored in EncryptedSharedPreferences backed by an Android Keystore master key. Values are encrypted with AES256-GCM and keys with AES256-SIV; the wrapping key never leaves the device.
- This encrypted preference file is intentionally excluded from Android cloud backup and device-to-device migration, so it is not copied to cloud servers or to a new device.
- Your notes, conversations, recordings, and other local content are protected by the Android application sandbox. Opedrgent does not claim that this content database is encrypted as a whole.

### Permissions

Runtime and special permissions are requested only when you first use the feature that needs them. Declining any single permission does not prevent the rest of the app from working, and every permission can be revoked later in system settings.

| Permission | Purpose | When it is requested |
|---|---|---|
| `RECORD_AUDIO` | Voice recognition (ASR) and audio recording / meeting transcription. | Only when you start voice input or a recording. |
| `CAMERA` | Taking photos to attach to notes or multimodal prompts. | Only when you choose to capture an image. |
| `POST_NOTIFICATIONS` | Notifying you during recording, model download, or background automation foreground services. | Only when such a foreground service is first used (Android 13+). |
| `READ_CALENDAR` / `WRITE_CALENDAR` | Reading, creating, updating, or deleting calendar events through the calendar tool. | Only when you first use calendar features. |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Optional environment / location tooling. | Off by default; requested only if you enable the location feature. |
| Health Connect read permissions + `ACTIVITY_RECOGNITION` | Reading steps, heart rate, calories, distance, sleep, and exercise as health-related context. | Off by default; requested only when you enable the health feature (see Health Connect Data above). |
| `SYSTEM_ALERT_WINDOW` | Drawing the floating assistant window over other apps. | Only when you enable the floating window. |
| Battery-optimization exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), `RECEIVE_BOOT_COMPLETED`, and the system Accessibility Service | Background automation: keeping scheduled tasks alive, restarting after reboot, and — only when you turn it on — observing the foreground app and performing taps or back gestures. | Off by default; requested only when you set up background automation. |

In addition, Opedrgent declares the install-time (normal) permissions `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, and `FOREGROUND_SERVICE`; these are used only to carry out the network calls you initiate, keep the device awake during processing, and run the foreground services listed above.

### Retention

Your on-device content remains on the device until you delete it. Opedrgent does not automatically expire or purge your notes, recordings, or downloaded models.

### Deletion

You can delete individual items at any time. Through the app's data-management options in Settings you can also clear content by category — review and critique-mirror records, recordings, notes, and downloaded models — or erase all of it at once. Deletion takes effect immediately and cannot be undone.

### Export

- Notes can be exported as plain text (`.txt`) or Markdown (`.md`).
- Conversations can be copied or shared as Markdown, or saved as a context package that bundles the session together with related memory, notes, and references.

Exported files are written to Opedrgent's private export directory and leave the device only when you explicitly choose to share them with another app.

### Backup and Restore

- The app can create a local backup archive (`.zip`) of your on-device content database together with non-sensitive app settings. The archive is produced entirely on-device and leaves the device only when you explicitly choose a destination through the system file picker (Storage Access Framework); no network transfer is involved.
- You can optionally include already-downloaded models in the backup; this is off by default and makes the archive noticeably larger.
- Your encrypted cloud API Keys and base URLs are **not** included in the backup. They are protected by an Android Keystore master key bound to this device, so they cannot be exported or restored onto another device; after restoring on a new device you will need to re-enter your cloud configuration.
- When restoring, the archive is verified for integrity and version compatibility before any data is written; if verification fails the restore is aborted and your existing data is left unchanged. A successful restore overwrites the current local data and typically requires restarting the app.

## Cloud Services

### Local-First by Default

Opedrgent is local-first. By default it does **not** initiate any network request to a cloud service. Language inference runs on-device through LiteRT-LM (Gemma), speech recognition runs primarily offline via Sherpa-ONNX, and text-to-speech uses the system engine by default. No API Key is configured out of the box, and no cloud endpoint is contacted until you explicitly opt in.

### Opt-In Cloud Endpoints

Cloud access is opt-in only. When you open the settings page and manually fill in a `baseUrl` and an API Key, conversations may be sent to the third-party endpoint you selected. If no API Key is configured, or if the cloud endpoint cannot be reached, Opedrgent automatically falls back to on-device processing.

The following preset endpoints are shipped with the app. Data is sent **directly from your device to the endpoint you choose**. Opedrgent does not operate these servers, does not relay traffic through its own servers, and does not retain any of the following content on its own infrastructure.

- **StepFun (阶跃星辰)**
  - Pay-as-you-go: `https://api.stepfun.com` (`/v1`)
  - Subscription plan: `https://api.stepfun.com` (`/step_plan/v1`)
- **Xiaomi MiMo (小米 MiMo)**
  - Pay-as-you-go: `https://api.xiaomimimo.com`
  - Personal / team Token Plan regional clusters:
    - China: `https://token-plan-cn.xiaomimimo.com`
    - Singapore: `https://token-plan-sgp.xiaomimimo.com`
    - Amsterdam (Europe): `https://token-plan-ams.xiaomimimo.com`
- **SiliconFlow (硅基流动)**
  - China: `https://api.siliconflow.cn`
  - International: `https://api.siliconflow.com`

### What Is Sent After You Opt In

Once you use a cloud model, the content required to answer your request is processed by the corresponding third party under that third party's own terms of service and privacy policy. This includes:

- your prompts;
- the conversation history included in the current request;
- the input passed to invoked tools;
- when you use multimodal features, the images, audio, or video you explicitly select.

Opedrgent makes no statement on, and takes no responsibility for, how these third parties process, store, or retain your data; their handling is governed solely by their own agreements.

### API Key Storage

Your API Key is stored only on this device, inside EncryptedSharedPreferences using AES256-GCM encryption. It is never uploaded to Opedrgent-operated servers.

### How to Revoke

You can withdraw cloud access at any time:

- clear the API Key (and baseUrl) in the settings screen, or
- enable only local models and leave cloud configuration unused.

After the key is removed, no further cloud requests will be made.

## 云端服务

### 默认本地优先

Opedrgent 采用本地优先设计。默认情况下，App **不会**向任何云端服务发起网络请求。语言推理经 LiteRT-LM 在端侧运行（Gemma），语音识别以 Sherpa-ONNX 离线识别为主，语音合成默认使用系统引擎。应用出厂未预置任何 API Key，在你主动配置并选择云端之前，不会连接任何云端端点。

### 按需启用的云端端点

云端访问完全由你自行选择开启。当你在设置页手动填写 `baseUrl` 与 API Key 后，对话内容才可能被发送到你选定的第三方端点。若未配置 API Key，或云端端点网络不可达，Opedrgent 会自动降级为端侧处理。

应用内置以下预设端点。数据由你的设备**直接发送至你所选的端点**。这些服务器由对应第三方运营，Opedrgent 不运营、不经自有服务器中转，也不在自有基础设施上留存下述任何内容。

- **阶跃星辰（StepFun）**
  - 按量付费：`https://api.stepfun.com`（`/v1`）
  - 订阅套餐：`https://api.stepfun.com`（`/step_plan/v1`）
- **小米 MiMo**
  - 按量付费：`https://api.xiaomimimo.com`
  - 个人 / 团队 Token Plan 区域集群：
    - 中国：`https://token-plan-cn.xiaomimimo.com`
    - 新加坡：`https://token-plan-sgp.xiaomimimo.com`
    - 欧洲（阿姆斯特丹）：`https://token-plan-ams.xiaomimimo.com`
- **硅基流动（SiliconFlow）**
  - 国内：`https://api.siliconflow.cn`
  - 国际：`https://api.siliconflow.com`

### 启用云端后会发送哪些数据

当你使用云端模型时，为完成请求所需的内容将由对应的第三方按其自身的服务条款与隐私政策处理，包括：

- 你输入的提示词；
- 当前请求所携带的对话历史；
- 被调用工具所接收的输入；
- 使用多模态能力时，你主动选定的图像、音频或视频。

对于上述第三方如何处理、存储或留存你的数据，Opedrgent 不作承诺，亦不承担责任；其处理规则完全由该第三方自身的协议约定。

### API Key 的存储方式

你的 API Key 仅以加密形式保存在本机的 EncryptedSharedPreferences 中（AES256-GCM），不会上传到 Opedrgent 运营的任何服务器。

### 如何撤回

你可以随时撤回云端访问：

- 在设置页清空 API Key（以及 baseUrl）；或
- 仅启用本地模型，不使用云端配置。

清空密钥后，应用将不再发起任何云端请求。

## 本地数据

Opedrgent 采用本地优先设计。你创建的内容——笔记、复盘与批判镜记录、录音、已下载模型——均保存在本机，除非你主动选择接入云端端点，否则不会上传（见下文「云端服务」）。

### 数据存放位置

你的内容保存在 Opedrgent 私有的应用沙箱目录中：

- 应用内部存储（`filesDir`）：内容数据库、笔记与笔记配图、录音、导出文件以及端侧模型；
- 应用私有外部目录（`getExternalFilesDir(...)`）：生成图片等较大体积的下载文件。

这些目录归 Opedrgent 自身所有，不对其他应用开放。在 Android 10 及以上系统，读写这些目录无需申请通用外部存储权限。与存储相关的声明仅有两项：限定在 Android 9 及以下生效的 `READ_EXTERNAL_STORAGE`，以及你自行挑选音频文件时使用的 `READ_MEDIA_AUDIO`。

### 安全

- 敏感配置（尤其是云端 API Key 与 baseUrl）保存在 EncryptedSharedPreferences 中，由 Android Keystore 生成的主密钥保护：值以 AES256-GCM 加密，键以 AES256-SIV 加密，主密钥不会离开设备。
- 该加密偏好文件被明确排除在 Android 云端备份与设备间迁移之外，因此不会被复制到云端服务器或迁移到新设备。
- 你的笔记、对话、录音等本地内容由 Android 应用沙箱保护。Opedrgent 不声称该内容数据库已做整库加密。

### 权限用途

运行时权限与特殊权限均在你首次使用对应功能时才申请。拒绝其中任何一项都不影响应用其余部分的正常使用，且你可随时在系统设置中撤销。

| 权限 | 用途 | 申请时机 |
|---|---|---|
| `RECORD_AUDIO` | 语音识别（ASR）与录音 / 会议转录。 | 仅在你开始语音输入或录音时申请。 |
| `CAMERA` | 拍摄照片，用于附加到笔记或多模态提问。 | 仅在你选择拍摄图片时申请。 |
| `POST_NOTIFICATIONS` | 在录音、模型下载或后台自动化前台服务运行时向你提示。 | 仅在首次使用此类前台服务时申请（Android 13+）。 |
| `READ_CALENDAR` / `WRITE_CALENDAR` | 通过日历工具读取、创建、更新或删除系统日历事件。 | 仅在你首次使用日历功能时申请。 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 可选的环境 / 位置工具。 | 默认关闭；仅在你启用位置功能时申请。 |
| Health Connect 读取权限 + `ACTIVITY_RECOGNITION` | 读取步数、心率、卡路里、距离、睡眠与运动，作为健康相关上下文。 | 默认关闭；仅在你启用运动健康功能时申请（见上文「Health Connect 数据」）。 |
| `SYSTEM_ALERT_WINDOW` | 在其他应用之上绘制悬浮助手窗口。 | 仅在你启用悬浮窗时申请。 |
| 电池优化豁免（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）、`RECEIVE_BOOT_COMPLETED` 与系统无障碍服务 | 后台自动化：维持定时任务存活、开机后重启，以及——仅在你主动开启时——感知前台应用并模拟点击或返回手势。 | 默认关闭；仅在你配置后台自动化时申请。 |

此外，Opedrgent 声明了安装即生效的普通权限 `INTERNET`、`ACCESS_NETWORK_STATE`、`WAKE_LOCK`、`FOREGROUND_SERVICE`，仅用于发起你主动请求的网络调用、在处理期间保持设备唤醒，以及运行上述前台服务。

### 数据保留

你的本地内容会一直保留在设备上，直到你主动删除。Opedrgent 不会自动过期或清理你的笔记、录音或已下载模型。

### 数据删除

你可以随时逐条删除内容。在应用「设置」的数据管理选项中，你还可以按类别清除——复盘与批判镜记录、录音、笔记、已下载模型——或一次性清空全部本地内容。删除立即生效，且不可恢复。

### 数据导出

- 笔记可导出为纯文本（`.txt`）或 Markdown（`.md`）。
- 对话可复制或以 Markdown 分享，也可保存为上下文归档包，将会话连同相关记忆、笔记与引用一并打包。

导出文件写入 Opedrgent 私有的导出目录，仅在你主动选择分享给其他应用时才会离开设备。

### 备份与恢复

- 应用可将你的端侧内容数据库连同非敏感应用设置打包为本地备份归档（`.zip`）。备份全程在本机生成，仅在你通过系统文件选择器（存储访问框架）主动选择保存位置时才会导出，过程不涉及任何网络传输。
- 你可选择将已下载模型一并纳入备份；该选项默认关闭，开启会显著增大归档体积。
- 加密的云端 API Key 与 base URL **不**纳入备份。它们由绑定本机的 Android Keystore 主密钥保护，无法导出或迁移到其他设备；在新设备上恢复后，你需要重新配置云端连接。
- 恢复前会先校验归档的完整性与版本兼容性，再写入任何数据；校验失败即中止恢复，原有数据保持不变。恢复成功会覆盖当前本地数据，且通常需要重启应用。

## Contact

If you have questions about this privacy policy, please open an issue on our [GitHub repository](https://github.com/HaohanHe/Opedrgent).
