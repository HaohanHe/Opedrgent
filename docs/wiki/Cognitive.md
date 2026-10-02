# 认知修炼（Cognitive Reflection）

> 本页说明 Opedrgent「认知修炼镜」：它与言行批判镜如何分工、认知偏差名称在其中扮演什么角色、什么情况下会如实说「本次未发现」，以及面对强烈自我否定或危机信号时的优先顺序。默认全程本地，云端须由你在设置中显式启用；数据流向与隐私口径以仓库根目录的 [PRIVACY.md](../../PRIVACY.md) 为准。

---

## 简体中文

### 它与言行批判镜如何分工

- **言行批判镜管「怎么说」**：把你录制的真实语音，对照你自己定义的理想人格行为基准，指出在表达方式上与基准的差距，并给出可以直接使用的替代说法；
- **认知修炼镜管「怎么想」**：它不对照任何人格基准，而是审视同一段话语背后的推断方式——在得出某个结论时，是不是有一个可商榷的思考习惯在悄悄起作用。

两者互补、互不替代：一个看你说出口的行为，一个看你心里的推理。

### 偏差名称只是参考知识，不是判定词表

这是认知修炼最重要的一条设计原则，请务必理解：

- 应用内置了一份常见认知偏差的参考词条（如过度概括、读心、情绪推理、确认偏误、沉没成本等）。它的唯一用途是作为背景知识随提示交给模型，帮助模型理解某类思维倾向大概是什么样子；
- **工程代码不会把你的任何语句与这些词条做字符串比对**。没有任何关键词、触发词表存在；转写里出现「永远」「完蛋」之类的词，不等于被判定为某种偏差；
- 模型只有在完整通读上下文、确信某种思维方式正在支撑你的某个结论时，才会在该条意见上标注对应的参考名；只是「有点像」而证据不足时，标注留空，照常给出替代视角即可。

### 逐字证据，没有就如实说

- 每一条意见都必须引用转写中真实出现的原句作为依据；质量门会独立核验引用是否逐字出现在原文中；
- 如果整段话语里找不到「正在支撑结论、可以商榷的思维方式」，模型会如实输出「本次未发现认知偏差」，不硬挑、不为了显得有分析而编造；
- 不贴标签、不对号入座：它描述的是这一次的具体推断，而不是给你这个人下结论。

### 给替代视角，而不是停在「你有偏差」

每一条意见都会落到一个更合理的替代视角，或者一个你可以反问自己的问题。例如把「我跟谁都处不好」这种全称判断，引回到「这一次的沟通具体卡在哪里」。目标是帮你换一个看事情的角度，而不是完成一次术语诊断。

### 自我否定与危机优先

认知修炼镜在完整语境中判断你的状态，而不是匹配固定词表：

- 当你正在强烈自我否定时，它会先停止挑错，把「整体自我攻击」拆回到几个具体的、可以改一改的推断，并给一个很小的下一步；
- 当语境中出现真实的危机信号时，它会停止一切分析，转为陪伴式回应，并提供本地求助资源；
- 语境不足时，它不贴标签，宁可少说。

### 数据与隐私

- 认知修炼的输入（你的转写文本）与报告默认保存在本机私有沙箱；云端模型仅在你显式配置并选择后才会被调用；
- 未配置云端时，分析经端侧模型完成，不出设备；
- 云端启用后，分析所需文本由设备直接发往你选定的第三方端点，处理规则以该第三方自身协议为准，Opedrgent 不经自有服务器中转。详见 [云端服务说明](Cloud-Services.md)。

---

## English

### How it divides the work with the Critique Mirror

- **The Critique Mirror governs how you speak**: it compares your recorded voice against the ideal-persona behavior baseline you define yourself, points out gaps in how you express yourself, and gives ready-to-use alternative phrasings.
- **The Cognitive Reflection Mirror governs how you think**: it compares you against no persona baseline at all. Instead it examines the reasoning behind the same words — whether some questionable habit of mind is quietly at work behind a given conclusion.

The two are complementary, not interchangeable: one looks at the behavior you voice, the other at the inference in your head.

### Bias names are reference knowledge, not a detection wordlist

This is the single most important design principle of Cognitive Reflection:

- The app ships with a reference glossary of common cognitive biases (overgeneralization, mind reading, emotional reasoning, confirmation bias, sunk cost, and so on). Its only use is to be handed to the model as background context, so the model knows roughly what each tendency looks like.
- **No application code ever string-matches your utterances against these terms.** There is no keyword list, no trigger list. Words like "always" or "ruined" in a transcript do not, by themselves, flag you as having any particular bias.
- Only after reading the whole context and genuinely concluding that a given way of thinking is actually supporting your conclusion will the model attach the corresponding reference name to that observation. If it merely "looks a bit like" something without sufficient evidence, the name is left blank and the alternative perspective is offered anyway.

### Verbatim evidence; say so when there is nothing

- Every observation must quote a sentence that actually appears in your transcript. An independent quality gate verifies that each quotation appears verbatim in the original text.
- If the whole passage contains no questionable way of thinking actually supporting a conclusion, the model states plainly: "No cognitive bias found this time." It does not hunt for problems or fabricate analysis to seem substantive.
- No labels, no box-fitting: it describes this specific inference, in this moment, rather than drawing a conclusion about who you are.

### Offer a perspective shift, not just "you have a bias"

Every observation lands on a more reasonable alternative perspective, or a question you can ask yourself. A sweeping claim like "nobody gets along with me" is led back to "what specifically went wrong in this one conversation". The goal is to hand you another angle on the situation, not to complete a terminology diagnosis.

### Self-negation and crisis come first

The Cognitive Mirror judges your state from the full context, not from fixed word lists:

- When you are in strong self-negation, it stops criticizing first, breaks a blanket self-attack back into a few specific inferences that could be adjusted, and offers one very small next step.
- When the context shows real crisis signals, it stops all analysis, shifts to a supportive response, and points to local help resources.
- When context is insufficient, it withholds the label and prefers to say less.

### Data and privacy

- Inputs (your transcript) and reports are kept in the on-device private sandbox by default; the cloud model is invoked only after you explicitly configure and select it.
- Without cloud configuration, analysis runs through the on-device model and never leaves the device.
- Once cloud is enabled, the text needed for analysis goes directly from your device to the third-party endpoint you chose; that third party's own agreement governs its handling, and Opedrgent relays nothing through its own servers. See [Cloud Services](Cloud-Services.md).

---

## 日本語

### 言行批判鏡との役割分担

- **言行批判鏡は「話し方」を管掌**: 録音した自分の声を、自分で定義した理想の人格行動基準と照合し、表現上のズレを指摘して、そのまま使える言い換えを提示します。
- **認知トレーニング鏡は「考え方」を管掌**: 人格基準とは一切照合せず、同じ発言の背後にある推論のしかたを眺めます。ある結論を導くとき、要検討な思考の癖が密かに働いていないかを見ます。

両者は補完関係で、取り替え不能です。口に出した行動を見るのが前者、心の中の推論を見るのが後者です。

### 偏差名は参考知識であって、判定用語リストではない

これが認知トレーニングの最も重要な設計原則です。

- アプリには代表的な認知バイアスの参考項目（過剰一般化、心の読みすぎ、感情的推理、確証バイアス、サンクコストなど）が組み込まれています。その唯一の用途は、背景知識としてプロンプトに添え、モデルが各傾向のおおよその輪郭を理解することです。
- **アプリのコードが、あなたの発言をこれらの語句と文字列照合することは一切ありません。** キーワード表もトリガー表も存在せず、書き起こしに「いつも」「完全に終わった」といった語が出てきても、それだけで何らかのバイアスと判定されることはありません。
- モデルは文脈全体を読んだうえで、ある思考様式が実際にあなたの結論を支えていると確信した場合に限り、その観察に対応する参考名を添えます。「少し似ている」程度で証拠不十分なら名前は空欄のまま、代わりの視点を普通に示します。

### 逐語的証拠。なければ「今回は見つかりません」

- すべての観察は、書き起こしに実際に現れた文を引用して根拠とします。独立した品質ゲートが、引用が原文に逐語的に現れるかを検証します。
- 冒頭から文末まで読んでも「結論を支えている、要検討な思考様式」が見当たらなければ、モデルは「今回は認知の偏りは見つかりません」と正直に出力し、無理に探したり、分析らしさを装って作りたりしません。
- ラベルを貼らず、当てはめもしません。描写するのは今この瞬間の具体的な推論であって、あなたという人間のレッテルではありません。

### 「偏差がある」で止めず、別の見方を示す

それぞれの観察は、より合理的な別視点か、自分に問い返せる質問に必ず着地します。「誰ともうまくいかない」という全称判断を、「今回の会話は具体的にどこでつまずいたか」へ戻すように。用語診断を完了することが目的ではなく、物事の見方をもう一つ手渡すことが目的です。

### 自己否定と危機を優先

認知トレーニング鏡は固定の語表ではなく文脈全体からあなたの状態を判断します。

- 強い自己否定にあるときは、まず細部の指摘を止め、「自分全体への攻撃」を具体的で手を付けやすい数個の推論に分解し、小さな次の一手を示します。
- 文脈から真の危機信号がうかがえるときは、一切の分析を中止し、寄り添う応答に切り替え、ローカルな相談資源を提示します。
- 文脈が不足しているときは、レッテルを貼らず、少なく留めます。

### データとプライバシー

- 入力（書き起こしテキスト）とレポートは既定で端末内のプライベートサンドボックスに保存されます。クラウドモデルは、あなたが設定で明示的に選択した後に限り呼び出されます。
- クラウド未構成なら、分析はオンデバイスモデルで完結し、端末から出ません。
- クラウド有効後は、分析に必要なテキストが端末から選んだ第三者エンドポイントへ直接送られ、その第三者自身の規約で処理されます。Opedrgent は自前サーバーを中継しません。詳しくは[クラウドサービス説明](Cloud-Services.md)を参照してください。

---

## 相关链接 / Related links / 関連リンク

- [个人修炼模式（Cultivation）](Cultivation.md) — 批判镜工程结构与数据流
- [行动跟进](Actions.md) — 镜鉴产出的替代说法如何进入待办闭环
- [送入批判镜](Mirror-Handoff.md) — 三个入口如何把文本送入镜鉴
- [云端服务说明](Cloud-Services.md)
- 返回 [Wiki 首页](Home.md)
