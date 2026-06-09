---
name: mimo-tts
description: 使用 MiMo V2.5 引擎进行高质量语音合成，支持基础合成/音色设计/音色克隆三种模式
version: 1.0.0
author: Opedrgent
require-secret: false
tags: [tts, 语音, 朗读, 配音, 唱歌, 念出]
category: general
children:
  - name: synthesize
    description: 基础语音合成，支持8个预置音色和风格控制
  - name: voicedesign
    description: 音色设计：通过文本描述生成自定义音色
  - name: voiceclone
    description: 音色克隆：通过音频样本复刻声音
---

# MiMo TTS 语音合成

使用 MiMo V2.5 引擎进行高质量语音合成服务。

## 子能力

### synthesize — 基础语音合成
- 支持 8 个预置音色
- 风格控制参数调节
- 适用于朗读、配音等场景

### voicedesign — 音色设计
- 通过自然语言描述目标音色特征
- 自动生成符合描述的自定义音色
- 支持年龄、性别、音调等维度描述

### voiceclone — 音色克隆
- 提供音频样本即可复刻声音
- 需要至少 10 秒以上的清晰音频
- 克隆效果取决于样本质量

## 使用方式

当用户需要语音输出时，选择合适的子能力模式并调用 TTS 工具。
