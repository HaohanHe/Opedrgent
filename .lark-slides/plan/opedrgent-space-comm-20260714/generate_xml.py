#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate slide XML files for Opedrgent competition deck."""

import os
import html

OUT_DIR = os.path.dirname(os.path.abspath(__file__))

# Color roles
C_PRIMARY = "rgba(11,26,46,1)"
C_SECONDARY = "rgba(30,58,95,1)"
C_ACCENT = "rgba(245,158,11,1)"
C_BG_LIGHT = "rgba(248,250,252,1)"
C_TEXT_DARK = "rgba(30,41,59,1)"
C_TEXT_LIGHT = "rgba(248,250,252,1)"
C_WHITE = "rgba(255,255,255,1)"
C_LIGHT_PANEL = "rgba(255,255,255,1)"

GRADIENT_DARK = "linear-gradient(135deg,rgba(11,26,46,1) 0%,rgba(30,58,95,1) 100%)"


def esc(text: str) -> str:
    return html.escape(text, quote=False)


def slide_start(bg: str = C_BG_LIGHT) -> str:
    return f'''<slide xmlns="http://www.larkoffice.com/sml/2.0">
  <style>
    <fill>
      <fillColor color="{bg}"/>
    </fill>
  </style>
  <data>'''


def slide_end(note: str = "") -> str:
    if note:
        note_xml = f'''
  </data>
  <note>
    <content textType="body">
      <p>{esc(note)}</p>
    </content>
  </note>
</slide>'''
    else:
        note_xml = '''
  </data>
</slide>'''
    return note_xml


def accent_bar() -> str:
    return f'''    <shape type="rect" topLeftX="0" topLeftY="0" width="8" height="540">
      <fill>
        <fillColor color="{C_ACCENT}"/>
      </fill>
    </shape>'''


def text_shape(x: int, y: int, w: int, h: int, text: str, text_type: str = "body",
               color: str = C_TEXT_DARK, align: str = "left", font_size: int = None, bold: bool = False) -> str:
    extra = ""
    if font_size:
        extra += f' fontSize="{font_size}"'
    if bold:
        extra += ' bold="true"'
    return f'''    <shape type="text" topLeftX="{x}" topLeftY="{y}" width="{w}" height="{h}">
      <content textType="{text_type}" textAlign="{align}" color="{color}"{extra}>
        <p>{esc(text)}</p>
      </content>
    </shape>'''


def multiline_text_shape(x: int, y: int, w: int, h: int, lines: list, text_type: str = "body",
                          color: str = C_TEXT_DARK, align: str = "left", font_size: int = None, bold: bool = False) -> str:
    extra = ""
    if font_size:
        extra += f' fontSize="{font_size}"'
    if bold:
        extra += ' bold="true"'
    items = "\n".join(f"        <p>{esc(line)}</p>" for line in lines)
    return f'''    <shape type="text" topLeftX="{x}" topLeftY="{y}" width="{w}" height="{h}">
      <content textType="{text_type}" textAlign="{align}" color="{color}"{extra}>
{items}
      </content>
    </shape>'''


def bullet_shape(x: int, y: int, w: int, h: int, bullets: list, text_type: str = "body",
                 color: str = C_TEXT_DARK, font_size: int = None) -> str:
    extra = f' fontSize="{font_size}"' if font_size else ""
    lis = "\n".join(f"          <li><p>{esc(b)}</p></li>" for b in bullets)
    return f'''    <shape type="text" topLeftX="{x}" topLeftY="{y}" width="{w}" height="{h}">
      <content textType="{text_type}" color="{color}"{extra}>
        <ul>
{lis}
        </ul>
      </content>
    </shape>'''


def icon(icon_type: str, x: int, y: int, w: int, h: int, color: str = C_ACCENT) -> str:
    return f'''    <icon iconType="{icon_type}" topLeftX="{x}" topLeftY="{y}" width="{w}" height="{h}">
      <fill>
        <fillColor color="{color}"/>
      </fill>
    </icon>'''


def rect(x: int, y: int, w: int, h: int, fill: str = None, border: str = None, alpha: float = None) -> str:
    fill_xml = ""
    if fill:
        if alpha is not None:
            # assume hex/rgb; convert? Simpler: use rgba if needed.
            fill_xml = f'''
      <fill>
        <fillColor color="{fill}"/>
      </fill>'''
        else:
            fill_xml = f'''
      <fill>
        <fillColor color="{fill}"/>
      </fill>'''
    border_xml = ""
    if border:
        border_xml = f'''
      <border color="{border}" width="1"/>'''
    return f'''    <shape type="rect" topLeftX="{x}" topLeftY="{y}" width="{w}" height="{h}" rx="8">{fill_xml}{border_xml}
    </shape>'''


def circle(x: int, y: int, r: int, fill: str, border: str = None) -> str:
    # shape type circle not in examples; use ellipse? Let's use shape with type ellipse.
    border_xml = f'''
      <border color="{border}" width="1"/>''' if border else ""
    return f'''    <shape type="ellipse" topLeftX="{x}" topLeftY="{y}" width="{r*2}" height="{r*2}">{border_xml}
      <fill>
        <fillColor color="{fill}"/>
      </fill>
    </shape>'''


def line(x1: int, y1: int, x2: int, y2: int, color: str = C_SECONDARY, width: int = 2) -> str:
    return f'''    <line startX="{x1}" startY="{y1}" endX="{x2}" endY="{y2}">
      <border color="{color}" width="{width}"/>
    </line>'''


def whiteboard_mermaid(x: int, y: int, w: int, h: int, code: str) -> str:
    # Escape CDATA end sequence if present
    safe_code = code.replace("]]>", "]]]]><![CDATA[>")
    return f'''    <whiteboard topLeftX="{x}" topLeftY="{y}" width="{w}" height="{h}">
      <mermaid>
        <![CDATA[
{safe_code}
        ]]>
      </mermaid>
    </whiteboard>'''


def card_with_icon(x: int, y: int, w: int, h: int, icon_type: str, title: str, bullets: list,
                   icon_color: str = C_ACCENT, fill: str = C_LIGHT_PANEL, border: str = "rgba(226,232,240,1)") -> str:
    shapes = []
    shapes.append(rect(x, y, w, h, fill=fill, border=border))
    shapes.append(icon(icon_type, x + 20, y + 20, 36, 36, icon_color))
    shapes.append(text_shape(x + 20, y + 68, w - 40, 30, title, text_type="sub-headline", color=C_TEXT_DARK, bold=True))
    if bullets:
        shapes.append(bullet_shape(x + 20, y + 105, w - 40, h - 120, bullets, font_size=13))
    return "\n".join(shapes)


def slide_1():
    data = []
    # title
    data.append(text_shape(80, 150, 800, 110, "Opedrgent：端侧 AI 知识工作站 · 空间通信智能助手",
                           text_type="title", color=C_TEXT_LIGHT, align="left", font_size=40, bold=True))
    data.append(text_shape(80, 280, 800, 50, "一款完全本地化的 Android AI 助手，现已延伸至业余卫星通联场景",
                           text_type="body", color="rgba(203,213,225,1)", align="left", font_size=18))
    data.append(text_shape(80, 340, 800, 30, "2026 国际空间通信挑战赛 · 匠造空间通信赛道",
                           text_type="caption", color=C_ACCENT, align="left", font_size=14))
    # rocket icon
    data.append(icon("iconpark/Travel/rocket.svg", 780, 80, 120, 120, C_ACCENT))
    # decorative orbit rings via whiteboard SVG
    svg = '''<svg xmlns="http://www.w3.org/2000/svg">
  <circle cx="400" cy="420" r="60" fill="none" stroke="rgba(245,158,11,0.25)" stroke-width="2"/>
  <circle cx="400" cy="420" r="100" fill="none" stroke="rgba(245,158,11,0.15)" stroke-width="2"/>
  <circle cx="400" cy="420" r="140" fill="none" stroke="rgba(245,158,11,0.08)" stroke-width="2"/>
  <circle cx="520" cy="380" r="8" fill="rgba(245,158,11,0.6)"/>
</svg>'''
    data.append(f'''    <whiteboard topLeftX="0" topLeftY="300" width="960" height="240">
{svg}
    </whiteboard>''')
    return slide_start(GRADIENT_DARK) + "\n" + "\n".join(data) + "\n" + slide_end(
        "开场：介绍 Opedrgent 定位，点明参赛赛道。强调‘端侧’与‘空间通信’两个关键词。")


def slide_2():
    data = [accent_bar()]
    data.append(text_shape(60, 40, 840, 60, "汇报提纲", text_type="title", color=C_PRIMARY, bold=True))
    sections = [
        ("01", "iconpark/Travel/rocket.svg", "背景与问题", "云端 AI 困境与端侧路径"),
        ("02", "iconpark/Health/brain.svg", "产品与能力", "端侧 AI 知识工作站"),
        ("03", "iconpark/Base/radar.svg", "Ham 模式", "业余卫星通联 Agent"),
        ("04", "iconpark/Charts/chart-line.svg", "竞赛价值", "契合“匠造空间通信”"),
    ]
    x0, y0, w, h, gap = 80, 140, 180, 260, 25
    for i, (num, ic, title, desc) in enumerate(sections):
        x = x0 + i * (w + gap)
        # card
        data.append(rect(x, y0, w, h, fill=C_WHITE, border="rgba(226,232,240,1)"))
        # number circle
        data.append(circle(x + 20, y0 + 20, 18, C_ACCENT))
        data.append(text_shape(x + 20, y0 + 26, 36, 24, num, text_type="caption", color=C_WHITE, align="center", bold=True, font_size=14))
        # icon
        data.append(icon(ic, x + (w - 48) // 2, y0 + 70, 48, 48, C_SECONDARY))
        # title
        data.append(text_shape(x + 15, y0 + 140, w - 30, 36, title, text_type="sub-headline", color=C_PRIMARY, align="center", bold=True, font_size=18))
        # desc
        data.append(text_shape(x + 15, y0 + 185, w - 30, 50, desc, text_type="body", color=C_TEXT_DARK, align="center", font_size=13))
    return slide_start() + "\n" + "\n".join(data) + "\n" + slide_end(
        "让评委快速把握结构：背景 → 产品 → Ham 模式 → 竞赛价值。")


def slide_3():
    data = []
    data.append(text_shape(80, 140, 800, 150, "“任何足够先进的科技，\n皆与魔法无异。”",
                           text_type="title", color=C_TEXT_LIGHT, align="center", font_size=40, bold=True))
    data.append(text_shape(80, 310, 800, 40, "—— Arthur C. Clarke，《克拉克三定律》，1962",
                           text_type="body", color="rgba(203,213,225,1)", align="center", font_size=16))
    data.append(text_shape(80, 380, 800, 60, "1945 年，克拉克在《Wireless World》预言地球同步通信卫星；\n今天，端侧 AI 让每个人都能把“魔法”装进口袋。",
                           text_type="body", color=C_ACCENT, align="center", font_size=16))
    return slide_start(GRADIENT_DARK) + "\n" + "\n".join(data) + "\n" + slide_end(
        "用克拉克名言建立科技史语境：从通信卫星到端侧 AI，技术一直在把“不可能”变为日常。")


def slide_4():
    data = [accent_bar()]
    data.append(text_shape(60, 40, 840, 60, "当前 AI 产品的共同困境", text_type="title", color=C_PRIMARY, bold=True))
    cards = [
        ("iconpark/Safe/shield.svg", "隐私泄露风险", ["对话、文档、位置上传云端", "合规与敏感数据外泄隐患"]),
        ("iconpark/Travel/globe.svg", "网络依赖与延迟", ["50–200 ms 网络往返", "野外/应急场景不可靠"]),
        ("iconpark/Character/close-one.svg", "离线不可用", ["断网即失去 AI 能力","关键任务无法保障"]),
    ]
    x0, y0, w, h, gap = 70, 140, 260, 220, 30
    for i, (ic, title, bullets) in enumerate(cards):
        x = x0 + i * (w + gap)
        data.append(rect(x, y0, w, h, fill=C_WHITE, border="rgba(226,232,240,1)"))
        data.append(icon(ic, x + (w - 48)//2, y0 + 20, 48, 48, C_ACCENT))
        data.append(text_shape(x + 15, y0 + 85, w - 30, 32, title, text_type="sub-headline", color=C_PRIMARY, align="center", bold=True, font_size=17))
        data.append(bullet_shape(x + 15, y0 + 125, w - 30, 75, bullets, font_size=13))
    # footer stat
    data.append(text_shape(80, 420, 800, 30, "据 IDC《2026 全球边缘 AI 市场洞察》，超过 45% 的企业级 AI 推理请求将在设备端完成。",
                           text_type="caption", color=C_SECONDARY, align="center", font_size=12))
    data.append(text_shape(80, 455, 800, 24, "来源：IDC, 2026; 中国信通院《端侧大模型数据治理法律要点研究》, 2025",
                           text_type="caption", color="rgba(148,163,184,1)", align="center", font_size=10))
    return slide_start() + "\n" + "\n".join(data) + "\n" + slide_end(
        "通过三个痛点与 IDC 数据，论证端侧 AI 是真实刚需。")


def slide_5():
    data = [accent_bar()]
    data.append(text_shape(60, 40, 840, 60, "两条路：云端 AI vs 端侧 AI", text_type="title", color=C_PRIMARY, bold=True))
    # left panel
    data.append(rect(80, 130, 380, 320, fill="rgba(30,58,95,0.08)", border=C_SECONDARY))
    data.append(text_shape(100, 150, 340, 40, "云端 AI", text_type="sub-headline", color=C_SECONDARY, bold=True, font_size=22))
    data.append(bullet_shape(100, 210, 340, 210, [
        "算力强大，模型规模大",
        "主动推荐，云端知识库",
        "数据需上传，依赖网络",
        "延迟与隐私存在天花板"
    ], font_size=15))
    # right panel
    data.append(rect(500, 130, 380, 320, fill="rgba(245,158,11,0.08)", border=C_ACCENT))
    data.append(text_shape(520, 150, 340, 40, "端侧 AI（Opedrgent）", text_type="sub-headline", color="rgba(180,83,9,1)", bold=True, font_size=22))
    data.append(bullet_shape(520, 210, 340, 210, [
        "数据不出设备，隐私安全",
        "毫秒级响应，断网可用",
        "利用手机 NPU/GPU 算力",
        "个人知识库随身携带"
    ], font_size=15))
    # vs badge
    data.append(circle(440, 250, 24, C_ACCENT))
    data.append(text_shape(416, 262, 48, 24, "VS", text_type="caption", color=C_WHITE, align="center", bold=True, font_size=14))
    return slide_start() + "\n" + "\n".join(data) + "\n" + slide_end(
        "对比云端与端侧，突出 Opedrgent 选择端侧的理由：隐私、实时、离线。")


def slide_6():
    data = [accent_bar()]
    data.append(text_shape(60, 40, 840, 60, "Opedrgent 是什么？", text_type="title", color=C_PRIMARY, bold=True))
    data.append(text_shape(80, 120, 840, 40, "一款完全本地化的 AI 知识工作站", text_type="sub-headline", color=C_SECONDARY, font_size=20))
    # big number
    data.append(text_shape(180, 180, 160, 150, "0", text_type="title", color=C_ACCENT, bold=True, font_size=140))
    data.append(text_shape(360, 220, 420, 80, "次云端数据上传\n语音、推理、记忆全在设备端完成",
                           text_type="body", color=C_TEXT_DARK, font_size=18))
    # three labels
    labels = [("iconpark/Hardware/microphone.svg", "语音转写本地"), ("iconpark/Health/brain.svg", "LLM 推理本地"), ("iconpark/Datas/database-config.svg", "记忆存储本地")]
    x0, y0, w, gap = 100, 380, 250, 35
    for i, (ic, label) in enumerate(labels):
        x = x0 + i * (w + gap)
        data.append(rect(x, y0, w, 90, fill=C_WHITE, border="rgba(226,232,240,1)"))
        data.append(icon(ic, x + 20, y0 + 21, 36, 36, C_ACCENT))
        data.append(text_shape(x + 70, y0 + 28, w - 85, 34, label, text_type="body", color=C_TEXT_DARK, font_size=15, bold=True))
    return slide_start() + "\n" + "\n".join(data) + "\n" + slide_end(
        "一句话定位产品，用“0 次上传”制造记忆点，再列三个本地层级。")


def slide_7():
    data = [accent_bar()]
    data.append(text_shape(60, 40, 840, 60, "核心闭环：输入 → 理解 → 记忆 → 洞察", text_type="title", color=C_PRIMARY, bold=True))
    mermaid = '''flowchart LR
    A[语音 / 文字 / 图片 / 链接 / 文件] --> B[ASR + LLM 理解]
    B --> C[记忆系统]
    C --> D[洞察引擎]
    D --> E[笔记 / 纪要 / 发芽报告]'''
    data.append(whiteboard_mermaid(60, 120, 840, 260, mermaid))
    # labels below
    data.append(text_shape(80, 400, 800, 80, "每一步都在设备本地完成：Sherpa-ONNX / MiMo ASR 转写、LiteRT-LM 推理、SQLite 三层记忆、Insight Sprout 洞察。",
                           text_type="body", color=C_SECONDARY, align="center", font_size=15))
    return slide_start() + "\n" + "\n".join(data) + "\n" + slide_end(
        "用流程图展示产品不是简单聊天机器人，而是完整的端侧知识工作流。")


def slide_8():
    data = [accent_bar()]
    data.append(text_shape(60, 40, 840, 60, "核心能力矩阵", text_type="title", color=C_PRIMARY, bold=True))
    caps = [
        ("iconpark/Hardware/microphone-one.svg", "全双工语音对话", "Interview 模式"),
        ("iconpark/Health/brain.svg", "知识发芽引擎", "4 阶段洞察"),
        ("iconpark/Charts/chart-line.svg", "会议自动纪要", "议题/结论/待办"),
        ("iconpark/Peoples/peoples.svg", "声纹识别", "说话人分离"),
        ("iconpark/Safe/shield.svg", "系统内录", "MediaProjection"),
        ("iconpark/Office/data-file.svg", "自然语言搜索", "语义检索"),
        ("iconpark/Hardware/radio.svg", "多引擎 ASR", "离线 + 在线"),
        ("iconpark/Base/setting.svg", "编辑团队协作", "8 角色写作"),
    ]
    x0, y0, w, h, gap_x, gap_y = 70, 130, 190, 130, 20, 30
    for i, (ic, title, desc) in enumerate(caps):
        col = i % 4
        row = i // 4
        x = x0 + col * (w + gap_x)
        y = y0 + row * (h + gap_y)
        data.append(rect(x, y, w, h, fill=C_WHITE, border="rgba(226,232,240,1)"))
        data.append(icon(ic, x + 15, y + 15, 28, 28, C_ACCENT))
        data.append(text_shape(x + 15, y + 55, w - 30, 30, title, text_type="body", color=C_PRIMARY, bold=True, font_size=14))
        data.append(text_shape(x + 15, y + 88, w - 30, 30, desc, text_type="caption", color=C_SECONDARY, font_size=12))
    return slide_start() + "\n" + "\n".join(data) + "\n" + slide_end(
        "快速展示 8 项核心能力，建立产品成熟度印象。")


def slide_9():
    data = [accent_bar()]
    data.append(text_shape(60, 40, 840, 60, "技术底座：全链路本地化", text_type="title", color=C_PRIMARY, bold=True))
    stacks = [
        ("iconpark/Hardware/microphone.svg", "语音转写", ["Sherpa-ONNX 离线", "MiMo ASR 在线", "27 种方言支持", "音频不离开设备"]),
        ("iconpark/Health/brain.svg", "AI 推理", ["LiteRT-LM 本地模型", "OpenAI/Anthropic 双协议", "NPU/GPU 加速", "断网完整可用"]),
        ("iconpark/Datas/database-config.svg", "记忆系统", ["SQLite 三层架构", "HippocampusIndex", "语义检索", "项目/会话/全局"]),
    ]
    x0, y0, w, h, gap = 80, 130, 260, 320, 30
    for i, (ic, title, bullets) in enumerate(stacks):
        x = x0 + i * (w + gap)
        data.append(rect(x, y0, w, h, fill=C_WHITE, border="rgba(226,232,240,1)"))
        data.append(icon(ic, x + (w - 56)//2, y0 + 25, 56, 56, C_ACCENT))
        data.append(text_shape(x + 20, y0 + 100, w - 40, 34, title, text_type="sub-headline", color=C_PRIMARY, align="center", bold=True, font_size=20))
        data.append(bullet_shape(x + 20, y0 + 145, w - 40, 160, bullets, font_size=14))
    return slide_start() + "\n" + "\n".join(data) + "\n" + slide_end(
        "讲清楚本地化的三层技术底座，让评委知道不是概念。")


def slide_10():
    data = []
    data.append(text_shape(80, 60, 800, 60, "隐私不是功能，是底线", text_type="title", color=C_TEXT_LIGHT, bold=True, align="center"))
    # big shield
    data.append(icon("iconpark/Safe/shield.svg", 416, 150, 128, 128, C_ACCENT))
    data.append(text_shape(80, 310, 800, 90, "你的每一次倾诉，都留在你的设备里。\n关闭应用后，所有处理立即停止；数据不会被用于模型训练或服务改进。",
                           text_type="body", color="rgba(203,213,225,1)", align="center", font_size=18))
    data.append(text_shape(80, 430, 800, 30, "这是代码层面的保证，而非一纸隐私政策。",
                           text_type="caption", color=C_ACCENT, align="center", font_size=14))
    return slide_start(GRADIENT_DARK) + "\n" + "\n".join(data) + "\n" + slide_end(
        "强调隐私是技术架构结果，对涉及呼号、位置、通联记录的卫星场景尤为重要。")


def slide_11():
    data = []
    data.append(text_shape(80, 180, 800, 70, "第二部分", text_type="title", color=C_ACCENT, align="left", font_size=28, bold=True))
    data.append(text_shape(80, 260, 800, 80, "从知识工作站\n到空间通信",
                           text_type="title", color=C_TEXT_LIGHT, align="left", font_size=44, bold=True))
    data.append(text_shape(80, 380, 600, 60, "设置里的 Ham 模式，让 Opedrgent 进入业余卫星通联领域。",
                           text_type="body", color="rgba(203,213,225,1)", align="left", font_size=18))
    data.append(icon("iconpark/Base/radar.svg", 760, 160, 140, 140, "rgba(245,158,11,0.2)"))
    data.append(icon("iconpark/Base/radar.svg", 800, 200, 80, 80, C_ACCENT))
    return slide_start(GRADIENT_DARK) + "\n" + "\n".join(data) + "\n" + slide_end(
        "章节转场，制造叙事转折，引出 Ham 模式。")


def slide_12():
    data = [accent_bar()]
    data.append(text_shape(60, 40, 840, 60, "Ham 模式：业余卫星通联 Agent", text_type="title", color=C_PRIMARY, bold=True))
    # left visual: settings toggle
    data.append(rect(80, 140, 360, 300, fill="rgba(30,58,95,0.05)", border="rgba(203,213,225,1)"))
    data.append(icon("iconpark/Base/setting.svg", 240, 170, 80, 80, C_SECONDARY))
    # toggle track
    data.append(rect(180, 270, 160, 50, fill="rgba(16,185,129,1)"))
    data.append(circle(320, 270, 25, C_WHITE))
    data.append(text_shape(120, 350, 280, 40, "Ham 模式（业余卫星）\n已开启", text_type="body", color=C_SECONDARY, align="center", font_size=15))
    # right bullets
    data.append(text_shape(480, 150, 440, 40, "开启后会发生什么？", text_type="sub-headline", color=C_PRIMARY, bold=True, font_size=22))
    data.append(bullet_shape(480, 200, 440, 220, [
        "请求位置权限，缓存观测站经纬度",
        "系统 Prompt 注入业余卫星通联上下文",
        "AI 获得 satellite_pass 工具调用能力",
        "可用自然语言查询卫星与过境窗口"
    ], font_size=16))
    return slide_start() + "\n" + "\n".join(data) + "\n" + slide_end(
        "展示 Ham 模式入口与开启后的系统级变化，建立可信度。")


def slide_13():
    data = [accent_bar()]
    data.append(text_shape(60, 40, 840, 60, "satellite_pass 工具能做什么？", text_type="title", color=C_PRIMARY, bold=True))
    # left list card
    data.append(rect(60, 120, 430, 220, fill=C_WHITE, border="rgba(226,232,240,1)"))
    data.append(icon("iconpark/Base/radar.svg", 90, 145, 40, 40, C_ACCENT))
    data.append(text_shape(145, 150, 320, 30, "action = list", text_type="sub-headline", color=C_PRIMARY, bold=True, font_size=18))
    data.append(bullet_shape(90, 195, 380, 120, [
        "返回业余卫星数据库",
        "名称 / NORAD ID / 频率 / 调制方式",
        "最低仰角与备注"
    ], font_size=14))
    # right passes card
    data.append(rect(520, 120, 400, 220, fill=C_WHITE, border="rgba(226,232,240,1)"))
    data.append(icon("iconpark/Charts/chart-line.svg", 550, 145, 40, 40, C_ACCENT))
    data.append(text_shape(605, 150, 290, 30, "action = passes", text_type="sub-headline", color=C_PRIMARY, bold=True, font_size=18))
    data.append(bullet_shape(550, 195, 350, 120, [
        "根据观测站位置计算未来窗口",
        "输出 AOS / LOS / 最大仰角 / 方位",
        "给出建议频率与调制方式"
    ], font_size=14))
    # sample table
    data.append(text_shape(60, 360, 840, 30, "示例输出（未来 24h 过境窗口）", text_type="caption", color=C_SECONDARY, font_size=14, bold=True))
    table_xml = f'''    <table topLeftX="60" topLeftY="400" width="840" height="110">
      <colgroup>
        <col width="140"/>
        <col width="180"/>
        <col width="180"/>
        <col width="140"/>
        <col width="120"/>
        <col width="80"/>
      </colgroup>
      <tr height="28">
        <td><content textType="caption" color="{C_TEXT_LIGHT}" bold="true"><p>卫星</p></content></td>
        <td><content textType="caption" color="{C_TEXT_LIGHT}" bold="true"><p>AOS</p></content></td>
        <td><content textType="caption" color="{C_TEXT_LIGHT}" bold="true"><p>LOS</p></content></td>
        <td><content textType="caption" color="{C_TEXT_LIGHT}" bold="true"><p>最大仰角</p></content></td>
        <td><content textType="caption" color="{C_TEXT_LIGHT}" bold="true"><p>方位</p></content></td>
        <td><content textType="caption" color="{C_TEXT_LIGHT}" bold="true"><p>频率</p></content></td>
      </tr>
      <tr height="28">
        <td><content textType="caption"><p>SO-50</p></content></td>
        <td><content textType="caption"><p>19:42</p></content></td>
        <td><content textType="caption"><p>19:54</p></content></td>
        <td><content textType="caption"><p>38°</p></content></td>
        <td><content textType="caption"><p>NE</p></content></td>
        <td><content textType="caption"><p>436.795</p></content></td>
      </tr>
      <tr height="28">
        <td><content textType="caption"><p>ISS</p></content></td>
        <td><content textType="caption"><p>20:15</p></content></td>
        <td><content textType="caption"><p>20:28</p></content></td>
        <td><content textType="caption"><p>62°</p></content></td>
        <td><content textType="caption"><p>NW</p></content></td>
        <td><content textType="caption"><p>145.800</p></content></td>
      </tr>
    </table>'''
    # table header background
    data.append(rect(60, 400, 840, 28, fill=C_SECONDARY))
    data.append(table_xml)
    return slide_start() + "\n" + "\n".join(data) + "\n" + slide_end(
        "讲清楚 satellite_pass 两个 action 的实际输出，用表格让信息可感。")


def slide_14():
    data = [accent_bar()]
    data.append(text_shape(60, 40, 840, 60, "技术实现：从 TLE 到过境窗口", text_type="title", color=C_PRIMARY, bold=True))
    mermaid = '''flowchart LR
    A[CelesTrak TLE] --> B[SGP4/SDP4 轨道模型]
    B --> C[观测站位置]
    C --> D[AOS / LOS / 最大仰角 / 方位 / 频率]'''
    data.append(whiteboard_mermaid(60, 110, 840, 200, mermaid))
    data.append(text_shape(80, 320, 800, 60, "代码实现：SatellitePassTool.kt 内置 ham_satellites.json 卫星数据库，\n通过位置服务获取经纬度，自动完成轨道预报与自然语言回复。",
                           text_type="body", color=C_SECONDARY, align="center", font_size=14))
    # file references as tags
    tags = [
        "SatellitePassTool.kt",
        "ham_satellites.json",
        "ApiSettings.kt",
        "MainViewModel.kt"
    ]
    x0 = 140
    for i, tag in enumerate(tags):
        x = x0 + i * 180
        data.append(rect(x, 390, 160, 32, fill="rgba(245,158,11,0.12)", border=C_ACCENT))
        data.append(text_shape(x, 396, 160, 24, tag, text_type="caption", color="rgba(180,83,9,1)", align="center", font_size=12))
    data.append(text_shape(80, 445, 800, 40, "学术依据：Hoots & Roehrich (1980) Spacetrack Report No. 3; Vallado et al. (2006) Revisiting Spacetrack Report #3; TLE 数据来自 CelesTrak / Space-Track。",
                           text_type="caption", color="rgba(148,163,184,1)", align="center", font_size=10))
    return slide_start() + "\n" + "\n".join(data) + "\n" + slide_end(
        "展示技术路径与源码文件引用，回应学术比赛对工程实现的关注。")


def slide_15():
    data = [accent_bar()]
    data.append(text_shape(60, 36, 840, 50, "学术与技术支撑", text_type="title", color=C_PRIMARY, bold=True))
    # quote block
    data.append(text_shape(80, 92, 800, 48, "“信息不是知识，知识不是智慧，智慧不是远见——但信息是这一切的第一步。”",
                           text_type="body", color=C_SECONDARY, align="center", font_size=14, bold=True))
    data.append(text_shape(80, 138, 800, 22, "—— Arthur C. Clarke, 2003",
                           text_type="caption", color="rgba(148,163,184,1)", align="center", font_size=10))
    table_xml = f'''    <table topLeftX="60" topLeftY="170" width="840" height="260">
      <colgroup>
        <col width="120"/>
        <col width="250"/>
        <col width="470"/>
      </colgroup>
      <tr height="28">
        <td><content textType="caption" color="{C_TEXT_LIGHT}" bold="true"><p>领域</p></content></td>
        <td><content textType="caption" color="{C_TEXT_LIGHT}" bold="true"><p>关键来源</p></content></td>
        <td><content textType="caption" color="{C_TEXT_LIGHT}" bold="true"><p>说明</p></content></td>
      </tr>
      <tr height="28">
        <td><content textType="caption"><p>轨道预报</p></content></td>
        <td><content textType="caption"><p>Hoots &amp; Roehrich, 1980; Vallado et al., 2006</p></content></td>
        <td><content textType="caption"><p>SGP4/SDP4 是 NASA 与 USSPACECOM 采用的行业标准解析轨道传播模型</p></content></td>
      </tr>
      <tr height="28">
        <td><content textType="caption"><p>TLE 数据</p></content></td>
        <td><content textType="caption"><p>CelesTrak / Space-Track / AMSAT</p></content></td>
        <td><content textType="caption"><p>Two-Line Element Sets 由 18 SDS 拟合观测生成，是 SGP4 的标准输入</p></content></td>
      </tr>
      <tr height="28">
        <td><content textType="caption"><p>业余卫星</p></content></td>
        <td><content textType="caption"><p>AMSAT / HamCQ / seeku.site</p></content></td>
        <td><content textType="caption"><p>SO-50、AO-91、ISS 等 FM 转发器频率、亚音与操作参数</p></content></td>
      </tr>
      <tr height="28">
        <td><content textType="caption"><p>端侧 AI</p></content></td>
        <td><content textType="caption"><p>IDC 2026; 中国信通院 2025; IEEE SDS 2025</p></content></td>
        <td><content textType="caption"><p>45%+ 企业推理将发生在设备端；端侧模型本地化、轻量化已成产业共识</p></content></td>
      </tr>
      <tr height="28">
        <td><content textType="caption"><p>隐私合规</p></content></td>
        <td><content textType="caption"><p>欧盟 AI 法案 / 个保法</p></content></td>
        <td><content textType="caption"><p>涉及位置、生物特征等敏感信息的 AI 处理“原则上应在本地完成”</p></content></td>
      </tr>
      <tr height="28">
        <td><content textType="caption"><p>工程实现</p></content></td>
        <td><content textType="caption"><p>python-sgp4 / sgp4 crate</p></content></td>
        <td><content textType="caption"><p>经官方 C++ 代码验证，位置误差低于 0.1 mm 相对标准实现</p></content></td>
      </tr>
    </table>'''
    data.append(rect(60, 170, 840, 28, fill=C_SECONDARY))
    data.append(table_xml)
    data.append(text_shape(60, 438, 840, 22, "补充：Zhan et al. (2026) PRISM (AAAI) 提出云-边协同的隐私感知路由；Kaplan Erol et al. (2026) EarthSight (arXiv) 研究低延迟卫星智能。",
                           text_type="caption", color="rgba(148,163,184,1)", font_size=9))
    data.append(text_shape(60, 465, 840, 24, "数据来源：CelesTrak、AMSAT、HamCQ、IDC《2026 全球边缘 AI 市场洞察》、中国信通院 2025、IEEE SDS 2025",
                           text_type="caption", color="rgba(148,163,184,1)", font_size=9))
    return slide_start() + "\n" + "\n".join(data) + "\n" + slide_end(
        "展示学术与工程基础，用权威来源与名言增强可信度。")


def slide_16():
    data = [accent_bar()]
    data.append(text_shape(60, 40, 840, 60, "典型应用场景", text_type="title", color=C_PRIMARY, bold=True))
    steps = ["查询卫星", "预测过境", "准备设备", "实时通联", "生成日志"]
    x0, y, gap = 90, 180, 170
    for i, step in enumerate(steps):
        x = x0 + i * gap
        data.append(circle(x + 30, y, 30, C_ACCENT))
        data.append(text_shape(x + 18, y + 18, 24, 24, str(i+1), text_type="caption", color=C_WHITE, align="center", bold=True, font_size=14))
        data.append(text_shape(x - 10, y + 75, 100, 40, step, text_type="body", color=C_PRIMARY, align="center", bold=True, font_size=14))
        if i < len(steps) - 1:
            data.append(line(x + 80, y + 30, x + gap - 10, y + 30, C_ACCENT, 2))
    # detail cards below
    details = [
        ("赛前准备", "快速确认频率、调制方式、最低仰角"),
        ("过境规划", "提前架设天线，锁定 AOS/LOS"),
        ("通联辅助", "语音询问实时方位与多普勒趋势"),
        ("日志生成", "自动记录时间/卫星/信号报告"),
    ]
    x0d, yd, wd, gapd = 60, 330, 200, 20
    for i, (t, d) in enumerate(details):
        x = x0d + i * (wd + gapd)
        data.append(rect(x, yd, wd, 130, fill=C_WHITE, border="rgba(226,232,240,1)"))
        data.append(text_shape(x + 15, yd + 15, wd - 30, 30, t, text_type="sub-headline", color=C_PRIMARY, bold=True, font_size=16))
        data.append(text_shape(x + 15, yd + 55, wd - 30, 65, d, text_type="body", color=C_SECONDARY, font_size=13))
    return slide_start() + "\n" + "\n".join(data) + "\n" + slide_end(
        "把功能落到具体场景，展示覆盖业余卫星操作全周期。")


def slide_17():
    data = [accent_bar()]
    data.append(text_shape(60, 40, 840, 60, "为什么适合“匠造空间通信”？", text_type="title", color=C_PRIMARY, bold=True))
    cards = [
        ("iconpark/Travel/globe.svg", "卫星通信", ["覆盖业余卫星数据库", "频率/调制方式查询", "符合业余无线电规范"]),
        ("iconpark/Base/radar.svg", "地面站", ["过境预测 AOS/LOS", "观测站位置感知", "可作为地面站辅助终端"]),
        ("iconpark/Hardware/microphone.svg", "演示装置", ["AI 语音交互演示", "端侧隐私安全架构", "软件+硬件可扩展"]),
    ]
    x0, y0, w, h, gap = 60, 130, 270, 320, 35
    for i, (ic, title, bullets) in enumerate(cards):
        x = x0 + i * (w + gap)
        data.append(rect(x, y0, w, h, fill=C_WHITE, border="rgba(226,232,240,1)"))
        data.append(icon(ic, x + (w - 56)//2, y0 + 25, 56, 56, C_ACCENT))
        data.append(text_shape(x + 15, y0 + 100, w - 30, 34, title, text_type="sub-headline", color=C_PRIMARY, align="center", bold=True, font_size=20))
        data.append(bullet_shape(x + 15, y0 + 145, w - 30, 150, bullets, font_size=14))
    return slide_start() + "\n" + "\n".join(data) + "\n" + slide_end(
        "直接回应赛项评审维度：卫星通信、地面站、演示装置。")


def slide_18():
    data = []
    data.append(text_shape(80, 140, 800, 120, "让空间通信的创新，\n发生在每个人口袋里",
                           text_type="title", color=C_TEXT_LIGHT, align="center", font_size=42, bold=True))
    data.append(text_shape(80, 290, 800, 60, "Opedrgent 用端侧 AI 降低业余卫星通联门槛\n期待与各位评委交流",
                           text_type="body", color="rgba(203,213,225,1)", align="center", font_size=18))
    data.append(text_shape(80, 400, 800, 40, "参赛团队：XXX  |  学校：XXX  |  联系：XXX",
                           text_type="caption", color=C_ACCENT, align="center", font_size=14))
    data.append(icon("iconpark/Travel/rocket.svg", 440, 460, 80, 80, C_ACCENT))
    return slide_start(GRADIENT_DARK) + "\n" + "\n".join(data) + "\n" + slide_end(
        "收尾，重申愿景，开放提问。")


def main():
    slides = [
        slide_1(), slide_2(), slide_3(), slide_4(), slide_5(),
        slide_6(), slide_7(), slide_8(), slide_9(), slide_10(),
        slide_11(), slide_12(), slide_13(), slide_14(), slide_15(),
        slide_16(), slide_17(), slide_18()
    ]
    for i, xml in enumerate(slides, 1):
        path = os.path.join(OUT_DIR, f"slide_{i:02d}.xml")
        with open(path, "w", encoding="utf-8") as f:
            f.write(xml)
        print(f"Wrote {path}")


if __name__ == "__main__":
    main()
