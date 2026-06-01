package top.hsyscn.opedrgent.tools.prompts

object SpeechToTextToolPrompt {
    const val DESCRIPTION = "语音转文字（Speech-to-Text）：将音频或视频文件中的语音内容高精度识别为文字。基于 Sherpa-ONNX 引擎，支持 Paraformer/SenseVoice/FunASR 模型，可自动从视频中提取音频轨道，支持中英文自动检测。"

    const val USAGE_GUIDELINES = """
## speech_to_text 工具使用规范

### 功能概述
将用户提供的音频或视频文件中的语音内容转换为结构化文字文本。采用端侧推理引擎 Sherpa-ONNX，数据完全本地处理，无需网络连接。适用于会议录音转写、视频字幕提取、语音备忘录整理、播客内容存档等场景。

### 参数说明

#### 必填参数
| 参数 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `uri` | String | 文件 URI 路径 | `content://media/external/audio/media/42` |

支持多种 URI 方案：
- **content://** — Android ContentProvider（推荐，通过系统文件选择器获取）
- **file://** — 本地文件绝对路径
- **/sdcard/...** — 简写路径（自动补全为 file://）
- **android.resource://** — 应用内资源文件

兼容参数别名：`file_uri`, `path`

#### 可选参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `language` | String | `auto` | 语言设置 |
| `enable_punctuation` | Boolean | `true` | 是否自动添加标点符号 |

**language 可选值：**
| 值 | 别名 | 说明 |
|----|------|------|
| `auto` | （默认） | 自动检测语言（推荐） |
| `zh` | chinese, 中文 | 强制中文识别模式 |
| `en` | english, 英文 | 强制英文识别模式 |

**enable_punctuation 说明：**
- `true` — 输出自动包含标点符号（句号、逗号、问号等）
- `false` — 输出原始无标点文本（适用于后续自行处理标点的场景）

### 支持的格式

#### 音频格式（直接解码）
| 格式 | 编码 | 推荐度 | 备注 |
|------|------|--------|------|
| MP3 | MPEG Audio Layer 3 | ⭐⭐⭐⭐ | 最常见，兼容性最佳 |
| WAV | PCM / IEEE Float | ⭐⭐⭐⭐⭐ | 无损推荐，解码最快 |
| M4A | AAC / ALAC | ⭐⭐⭐⭐ | Apple 设备常用 |
| AAC | Advanced Audio Coding | ⭐⭐⭐⭐ | 高效压缩 |
| OGG Vorbis | Vorbis | ⭐⭐⭐ | 开源格式 |
| FLAC | Free Lossless | ⭐⭐⭐⭐ | 无损压缩 |
| AMR | Adaptive Multi-Rate | ⭐⭐ | 录音机默认格式 |
| WMA | Windows Media | ⭐⭐ | Windows 常见 |
| OPUS | Opus Audio | ⭐⭐⭐⭐ | 高效低延迟 |
| PCM | Raw PCM Data | ⭐⭐⭐ | 原始数据 |

#### 视频格式（自动提取音频轨道）
| 格式 | 容器 | 推荐度 | 备注 |
|------|------|--------|------|
| MP4 | ISO Base Media | ⭐⭐⭐⭐⭐ | 最通用，H.264+AAC 推荐 |
| MKV | Matroska | ⭐⭐⭐⭐ | 开源容器，支持广泛 |
| AVI | Audio Video Interleave | ⭐⭐⭐ | 传统格式 |
| MOV | QuickTime | ⭐⭐⭐⭐ | Apple 设备原生 |
| WebM | WebM (VP8/VP9) | ⭐⭐⭐⭐ | Web 优化 |
| FLV | Flash Video | ⭐⭐ | 直播流媒体 |
| WMV | Windows Media Video | ⭐⭐ | Windows 原生 |
| 3GP | 3GPP | ⭐⭐ | 手机录制旧格式 |

### 使用限制

| 限制项 | 限制值 | 说明 |
|--------|--------|------|
| 最大时长 | 30 分钟 (1800 秒) | 超长音频需分段处理 |
| 最小时长 | > 1 秒 | 过短可能无法产生有效结果 |
| 文件大小建议 | < 100 MB | 过大可能导致内存不足 |
| 最大单段 | 30 秒 | 长音频自动分段识别 |

### 使用示例

**示例 1 — 基本用法（自动检测语言）**
```
speech_to_text(uri="content://media/external/audio/media/12345")
```

**示例 2 — 指定中文 + 标点**
```
speech_to_text(uri="/sdcard/Download/meeting.mp3", language="zh", enable_punctuation=true)
```

**示例 3 — 视频文件提取字幕**
```
speech_to_text(uri="file:///sdcard/DCIM/Camera/lecture.mp4", language="zh")
```

**示例 4 — 英文音频无标点**
```
speech_to_text(uri="content://...", language="en", enable_punctuation=false)
```

**示例 5 — 通过别名参数调用**
```
speech_to_text(file_uri="/sdcard/recording.wav", path="/sdcard/recording.wav")
```

### 返回结果格式

成功时返回 Markdown 格式的完整报告：

```
✅ 转录完成

📄 **转录文本**:
[完整的识别文本内容]

📊 **统计信息**:
- 时长: 5:32
- 字数: 1,247 字符
- 引擎: Sherpa-ONNX (Paraformer) (SENSE_VOICE_SMALL)
- 置信度: 94.2%
- 处理时间: 3.2 秒
- 媒体类型: 音频

📑 **分段详情**（共 N 段）:
  [1] `0:00` → `0:28` (95%): 第一段转录文本...
  [2] `0:27` → `0:55` (92%): 第二段转录文本...

💡 **操作建议**:
- 点击「复制」可复制全文
- 点击「发送给 AI 分析」可让 LLM 总结要点
- 如需导出，可请求 AI 将内容整理为 Markdown 或其他格式
```

### 错误处理与解决指南

工具会针对不同错误类型返回具体的诊断信息和解决方案：

| 错误类型 | 触发条件 | 解决方向 |
|----------|----------|----------|
| 缺少 URI | 未提供 uri/file_uri/path | 提供有效的文件路径 |
| 无效 URI | URI 格式无法解析 | 检查路径格式是否正确 |
| 时长超限 | 音频超过 30 分钟 | 截取前 30 分钟处理 |
| 权限不足 | 存储权限未授予 | 在设置中授权存储访问 |
| 文件损坏 | 文件无法读取或时长为 0 | 确认文件完整性并重新获取 |
| 格式异常 | 采样率等元数据不合理 | 转换为标准 WAV 格式 |
| 视频提取失败 | 视频无音频轨道或不支持 | 用 FFmpeg 预提取音频 |
| 引擎不可用 | STT 模型未下载/初始化 | 下载模型文件 |
| 识别为空 | 无有效语音内容 | 检查音频质量和语言设置 |
| 模型未就绪 | 引擎未完成初始化 | 等待模型加载完成 |
| 格式不支持 | 不在支持列表中的编码 | 转换为 MP3/WAV/M4A |
| 内存不足 | 设备 RAM 不足 | 切换轻量模型或缩短音频 |

### 最佳实践

1. **音质优先**
   - 优先使用清晰、低噪音的录音源
   - 信噪比越高，识别准确率越好
   - 建议 16kHz 以上采样率

2. **语言匹配**
   - 默认 auto 模式对中英混合内容效果良好
   - 纯中文/纯英文场景可显式指定以提升准确率
   - 多语言混合时使用 auto 模式

3. **视频处理**
   - 视频文件无需预先转换，工具自动提取音频
   - 推荐使用 H.264 视频 + AAC 音频编码的 MP4
   - 如果提取失败，可用 FFmpeg 预处理

4. **长音频策略**
   - 30 分钟以内：直接处理，自动分段
   - 超过 30 分钟：先裁剪再处理，或分多次调用
   - 会议录音建议按议题拆分后分别转写

5. **结果优化**
   - 如识别不准，尝试指定正确的 language 参数
   - 背景噪音大时可先用降噪工具预处理
   - 结果可通过 LLM 进行摘要、提炼要点等后续处理

6. **性能调优**
   - 低端设备 (<4GB RAM) 使用 FunASR-Nano 模型
   - 中端设备 (4-6GB RAM) 使用 SenseVoice-Small
   - 高端设备 (≥6GB RAM) 使用 Paraformer 获得最高精度

### 技术细节

- **引擎**: Sherpa-ONNX (K2-FSA)
- **模型**: Paraformer / SenseVoice / FunASR-Nano
- **推理**: 完全本地执行，无需网络
- **输出采样率**: 16 kHz Mono PCM Float
- **分段策略**: 基于静音检测的智能切分（30s/段，500ms 重叠）
- **线程调度**: IO 线程池执行，不阻塞主线程
"""

    fun getToolPrompt(): String = "$DESCRIPTION\n$USAGE_GUIDELINES"
}
