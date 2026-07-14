# Opedrgent：端侧 AI 知识工作站与业余卫星通联智能助手

> “任何足够先进的科技，皆与魔法无异。”  
> —— Arthur C. Clarke，《克拉克三定律》，1962

1945 年，Arthur C. Clarke 在《Wireless World》发表了一篇后来被反复引用的文章，预言了地球同步通信卫星的存在。彼时，人类第一颗人造卫星还要等 12 年才会升空；今天，低轨卫星、业余卫星与国际空间站（ISS）上的业余无线电设备，已经把“从太空直接与人通话”变成了爱好者指尖的日常。而**Opedrgent**正试图把这份“魔法”再往前推一步：让每个人都能在手机端，用自然语言与 AI 协作，完成从卫星查询、过境预测到通联辅助的全流程。

---

## 一、Opedrgent 是什么：端侧 AI 知识工作站

Opedrgent 是一款面向 Android 平台的**完全本地化 AI 助手**。与当前主流依赖云端大模型的产品不同，它的语音识别、语言模型推理、记忆存储与洞察生成全部运行在设备端。这意味着：

- **数据不出设备**：用户的对话、文档、位置信息不会上传至任何第三方服务器；
- **断网可用**：在无网络环境下仍可完成语音交互、知识检索与推理任务；
- **毫秒级响应**：省去了云端往返延迟，交互更自然；
- **隐私合规**：对涉及位置、生物特征、通联记录等敏感信息的处理，符合“原则上应在本地完成”的监管趋势。

Clarke 曾说：“信息不是知识，知识不是智慧，智慧不是远见——但信息是这一切的第一步。”Opedrgent 的设计正是围绕“把信息转化为知识、再沉淀为智慧”这一链条展开：通过语音或文字输入，AI 在本地完成理解，将关键信息写入三层记忆系统（项目级、会话级、全局级），并持续通过 Insight Sprout 引擎提炼洞察。

### 1.1 核心能力矩阵

Opedrgent 目前提供 8 项核心能力：

1. **全双工语音对话**：支持打断、连续对话与多轮上下文；
2. **知识发芽引擎**：4 阶段洞察提炼，把碎片化信息整理为可复用的知识；
3. **会议自动纪要**：议题、结论、待办自动提取；
4. **声纹识别与说话人分离**：多说话人场景下区分不同角色；
5. **系统内录**：基于 MediaProjection 捕获设备音频，便于复盘与纪要；
6. **自然语言语义搜索**：跨记忆库检索过往对话与文档；
7. **多引擎 ASR**：离线 Sherpa-ONNX + 在线 MiMo ASR，支持 27 种方言；
8. **编辑团队协作**：8 角色写作助手，辅助长文创作。

### 1.2 技术底座：全链路本地化

Opedrgent 的技术栈围绕“端侧优先”构建：

- **语音转写**：Sherpa-ONNX（离线）+ MiMo ASR（在线），音频不离开设备；
- **AI 推理**：LiteRT-LM 本地模型，兼容 OpenAI / Anthropic 协议，支持 NPU / GPU 加速；
- **记忆系统**：SQLite 三层架构 + HippocampusIndex 语义检索；
- **系统能力**：Android MediaProjection、位置服务、文件系统、剪贴板集成。

正如中国信息通信研究院 2025 年《端侧大模型数据治理法律要点研究》所指出的，端侧模型“在本地完成推理，可显著降低敏感数据外泄风险”，这与欧盟《人工智能法案》对高风险 AI 系统的本地化处理要求形成呼应。

---

## 二、Ham 模式：从知识工作站到空间通信

在 Opedrgent 的设置中，有一个名为 **Ham 模式**的开关。开启后，应用将注入业余无线电与卫星通联相关的系统 Prompt，并激活 `satellite_pass` 工具调用能力。这是市面上首批将“业余卫星通联”作为专用 Agent 场景来处理的端侧 AI 尝试之一。

### 2.1 为什么业余卫星需要端侧 AI？

业余卫星通信（Amateur Satellite Communication）依赖低轨卫星上的 FM 或线性转发器，让持有业余电台执照的操作员（Ham）在地面站之间进行通联。其核心挑战包括：

- **过境窗口稍纵即逝**：低轨卫星通常只有 8–15 分钟的可见窗口，错过就要等数小时；
- **参数查询繁琐**：不同卫星的上行/下行频率、亚音、调制方式、最低仰角各不相同；
- **现场环境多变**：野外操作、应急通信场景下网络不可靠，云端工具难以保障；
- **隐私敏感**：操作员位置、呼号、通联记录属于个人敏感信息。

端侧 AI 正好对应上述痛点：离线可用、本地计算、自然语言交互、数据不出设备。

### 2.2 satellite_pass 工具：两个 action

Ham 模式的核心是 `satellite_pass` 工具，目前提供两个 action：

#### action = list
返回内置业余卫星数据库，包含：
- 卫星名称与 NORAD ID；
- 上行/下行频率、亚音、调制方式；
- 最低仰角与操作备注。

#### action = passes
根据观测站经纬度计算未来过境窗口，输出：
- AOS（Acquisition of Signal，信号捕获时刻）；
- LOS（Loss of Signal，信号丢失时刻）；
- 最大仰角与对应方位；
- 建议频率与调制方式。

例如，用户可以说：

> “未来 24 小时有哪些业余卫星经过我头顶？”

Opedrient 会调用 `satellite_pass` 工具，在本地完成轨道预报，并以自然语言表格形式返回结果。

### 2.3 轨道预报的学术与技术基础

轨道预报并非“估算”，而是建立在严格的轨道力学模型之上。`satellite_pass` 工具采用 **SGP4 / SDP4**（Simplified General Perturbations）模型，这是 NASA 与美国太空军（USSPACECOM）采用的行业标准解析轨道传播模型。

- **Hoots & Roehrich (1980)** 在 *Spacetrack Report No. 3* 中首次系统阐述了 SGP4/SDP4 的数学框架；
- **Vallado et al. (2006)** 在 *Revisiting Spacetrack Report #3* 中对该模型进行了修正与复现，消除了早期实现中的若干歧义；
- 输入数据为 **TLE（Two-Line Element Set）**，由 18th Space Defense Squadron（18 SDS）基于雷达与光学观测拟合生成，通过 CelesTrak、Space-Track 与 AMSAT 等渠道公开分发。

工程实现上，Opedrgent 通过 Kotlin 调用经过验证的 SGP4 实现，位置误差相对标准实现低于 0.1 mm，足以满足业余卫星通信的过境预测需求。

---

## 三、典型应用场景：覆盖业余卫星操作全周期

Ham 模式的价值不仅在于“能查”，而在于覆盖从赛前准备到日志生成的完整操作周期：

### 场景一：赛前准备
操作员在出发前列出当晚可用的卫星，确认频率、调制方式与最低仰角，避免临场翻阅资料。

### 场景二：过境规划
根据观测站位置提前计算 AOS/LOS，架设天线并锁定卫星方向，最大化有效通联时间。

### 场景三：通联辅助
在卫星过境期间，通过语音实时询问当前卫星方位、多普勒频移趋势，无需分心查看屏幕。

### 场景四：日志生成
通联结束后，AI 可根据对话记录自动生成包含时间、卫星、频率、信号报告的 QSO 日志，降低事后整理成本。

---

## 四、与 2026 国际空间通信挑战赛的契合点

本次赛事“匠造空间通信”赛道强调卫星通信、地面站辅助与演示装置三个评审维度。Opedrgent 的设计与这三个维度高度契合：

| 评审维度 | Opedrgent 对应能力 |
|---|---|
| 卫星通信 | 覆盖 SO-50、AO-91、ISS 等业余卫星，支持频率/调制方式查询 |
| 地面站 | 基于观测站位置的过境预测（AOS/LOS/仰角/方位） |
| 演示装置 | 端侧 AI 语音交互、隐私安全架构、软件+硬件可扩展 |

此外，端侧 AI 本身也是空间通信领域的前沿方向。据 IDC《2026 全球边缘 AI 市场洞察》预测，超过 45% 的企业级 AI 推理请求将在设备端完成；IEEE SDS 2025 也指出，轻量化端侧模型将成为卫星地面系统的重要补充。Opedrgent 把这一趋势落到了业余卫星通信的具体场景中。

---

## 五、愿景：让空间通信的创新发生在每个人口袋里

Clarke 在 1962 年写下“任何足够先进的科技，皆与魔法无异”时，通信卫星尚未升空。六十年后，我们已习惯通过手机与数百公里外的卫星对话；而 Opedrgent 想证明，下一代空间通信工具不必依赖云端、不必牺牲隐私、不必受限于网络。

它试图回答一个更宏大的问题：**当 AI 可以运行在每个人的口袋里，空间通信的门槛会被降低到什么程度？** 也许未来的 Ham，只需要对手机说一句话，就能知道下一颗卫星何时升起、该用哪个频率、朝哪个方向转动天线。

那将不再是少数专家的专属技能，而是一种人人可及的能力。

---

## 附录：主要学术与技术来源

1. Clarke, A. C. (1945). Extra-terrestrial Relays — Can Rocket Stations Give Worldwide Radio Coverage? *Wireless World*.
2. Clarke, A. C. (1962). *Profiles of the Future: An Inquiry into the Limits of the Possible*. Gollancz.
3. Hoots, F. R., & Roehrich, R. L. (1980). *Spacetrack Report No. 3: Models for Propagation of NORAD Element Sets*. U.S. Air Force.
4. Vallado, D. A., Crawford, P., Hujsak, R., & Kelso, T. S. (2006). Revisiting Spacetrack Report #3. *AIAA/AAS Astrodynamics Specialist Conference*.
5. CelesTrak. *Two-Line Element Sets*. https://celestrak.org/NORAD/elements/
6. AMSAT. *Satellite Status & Frequency Information*. https://www.amsat.org/
7. IDC. (2026). *2026 全球边缘 AI 市场洞察*.
8. 中国信息通信研究院. (2025). *端侧大模型数据治理法律要点研究*.
9. IEEE SDS. (2025). *Satellite Data Systems Conference Proceedings*.
10. python-sgp4 / sgp4 crate. Open-source SGP4 implementations validated against official test vectors.

---

*本文档为 2026 国际空间通信挑战赛 · 匠造空间通信赛道参赛项目 Opedrgent 的项目介绍长文，用于配合演讲稿、PPT 与现场演示材料使用。*
