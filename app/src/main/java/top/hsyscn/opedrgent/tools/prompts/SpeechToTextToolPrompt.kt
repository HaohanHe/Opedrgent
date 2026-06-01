package top.hsyscn.opedrgent.tools.prompts

object SpeechToTextToolPrompt {
    const val DESCRIPTION = "语音转文字：将音频或视频文件中的语音内容识别为文字。支持多种音视频格式，可自动从视频中提取音频轨道进行识别。"

    const val USAGE_GUIDELINES = """
## speech_to_text 工具使用规范

### 功能说明
将用户提供的音频或视频文件中的语音内容转换为文字文本。适用于会议录音、视频字幕提取、语音备忘录转写等场景。

### 必填参数
- uri: 文件URI路径（支持 content://、file://、/sdcard/ 等格式）

### 可选参数
- language: 语言设置
  - auto（默认）: 自动检测语言
  - zh / chinese / 中文: 中文识别
  - en / english / 英文: 英文识别
- enable_punctuation: 是否添加标点符号
  - true（默认）: 自动添加标点
  - false: 原始输出无标点

### 支持的格式
**音频格式：**
- MP3, WAV, M4A, AAC, FLAC, OGG, AMR

**视频格式（自动提取音频轨道）：**
- MP4, AVI, MKV, MOV, WebM

### 使用限制
- 最大时长：30 分钟
- 最小时长：建议 > 1 秒（过短可能无法识别）
- 文件大小：建议 < 100MB

### 最佳实践
1. 优先使用清晰的录音文件，避免背景噪音过大
2. 视频文件会自动提取音频轨道，无需预先转换
3. 长文件会自动分段处理，返回带时间戳的分段结果
4. 如果识别结果不准确，可尝试指定 language 参数

### 返回结果格式
```
✅ 语音转文字完成
- 识别文字：（完整转录文本）
- 置信度：XX%
- 引擎：SHERPA_ONNX
- 模型：（使用的模型名称）
- 耗时：XXXms
- 音频时长：MM:SS
- 分段详情：（多段时显示）
```

### 错误处理
- 文件不存在或无法访问 → 提示检查文件路径
- 格式不支持 → 建议转换为支持的格式
- 引擎不可用 → 提示下载STT模型
- 识别为空 → 可能是无语音内容或噪音过大
"""

    fun getToolPrompt(): String = "$DESCRIPTION\n$USAGE_GUIDELINES"
}
