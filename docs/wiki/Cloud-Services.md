# 云端服务说明

> 本文介绍 Opedrgent 的本地优先设计、云端接入方式、支持的服务商与端点、密钥类型限制、限流与故障降级策略，以及隐私与撤回方式。法律条款以仓库根目录的 [PRIVACY.md](../../PRIVACY.md) 为准。

---

## 本地优先

Opedrgent 默认全部在端侧完成，不发起任何云端请求：

- 语言模型：经 LiteRT-LM 在端侧运行 Gemma；
- 语音识别（ASR）：以 Sherpa-ONNX 离线识别为主；
- 语音合成（TTS）：默认使用系统引擎。

应用不预置任何 API Key。只有你在「设置」中手动填写 baseUrl 与 API Key，并实际使用云端模型后，才会向你所选的端点发送数据。

## 云端 opt-in 后会发生什么

启用云端后，为完成请求所需的数据由设备直接发送到你选定的第三方端点，由该第三方按其自身服务条款与隐私政策处理，包括：

- 你输入的提示词；
- 当前请求所携带的对话历史；
- 被调用工具所接收的输入；
- 多模态场景下你主动选定的图像、音频或视频。

这些服务器由对应第三方运营。Opedrgent 不运营这些服务器，不经自有服务器中转，也不在自有服务器上留存上述内容。第三方如何处理与留存数据，以其自身协议为准。

## 支持的服务商与端点

应用设置页内置以下预设端点，选择后会自动填入对应 baseUrl：

### 阶跃星辰 StepFun

- 按量付费：`https://api.stepfun.com`（`/v1`）
- 订阅套餐：`https://api.stepfun.com`（`/step_plan/v1`）

### 小米 MiMo

- 按量付费：`https://api.xiaomimimo.com`
- Token Plan 订阅区域集群：
  - 中国：`https://token-plan-cn.xiaomimimo.com`
  - 新加坡：`https://token-plan-sgp.xiaomimimo.com`
  - 欧洲（阿姆斯特丹）：`https://token-plan-ams.xiaomimimo.com`

### 硅基流动 SiliconFlow

- 国内：`https://api.siliconflow.cn`
- 国际：`https://api.siliconflow.com`

## 密钥类型与 Token Plan 限制

以小米 MiMo 为例，密钥前缀决定计费方式与允许的使用范围：

| 前缀 | 类型 | 说明 |
|------|------|------|
| `sk-` | 按量付费 | 按实际 token 用量计费，适合在各类自建客户端中使用 |
| `tp-` | 个人 Token Plan | 订阅制套餐 |
| `ttp-` | 团队 Token Plan | 订阅制套餐（团队） |

需要特别注意：按小米 Token Plan 服务条款，该订阅仅限 AI 编程工具使用，禁止用于自动化脚本或自建应用后端。在本 App 这类移动客户端中使用 Token Plan 密钥，属于条款约定之外的自建客户端场景，存在被终止订阅的风险。因此当设置页检测到密钥以 `tp-` 或 `ttp-` 开头时，会显示使用提醒；如需在本 App 中长期使用云端能力，建议改用按量付费的 `sk-` 密钥。

## 限流与故障降级

- 遇到 HTTP 429（请求过多）时，客户端会按服务端返回的退避建议进行重试退避，避免高频请求打满配额。
- 未配置密钥、端点网络不可达或云端调用失败时，应用自动降级回端侧模型，保证基本对话能力不中断。

## 隐私与撤回方式

- API Key 仅经 EncryptedSharedPreferences（AES256-GCM）加密后保存在本机，不上传任何由 Opedrgent 运营的服务器。
- 撤回云端访问：在设置页清空 API Key 与 baseUrl，或仅启用本地模型而不使用云端配置。清空密钥后，应用不再发起任何云端请求。
- 完整中英双语条款见仓库根目录 [PRIVACY.md](../../PRIVACY.md)。
