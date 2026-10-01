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

## Contact

If you have questions about this privacy policy, please open an issue on our [GitHub repository](https://github.com/HaohanHe/Opedrgent).
