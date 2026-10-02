# 送入批判镜（Send to Mirror）

> 本页说明 Opedrgent 的「一键送入批判镜」：录音转写、洞察、笔记三个入口如何由你主动把文本送进修炼镜，送入之后发生什么、不发生什么。默认全程本地，云端须由你在设置中显式启用；数据流向与隐私口径以仓库根目录的 [PRIVACY.md](../../PRIVACY.md) 为准。

---

## 简体中文

### 三个入口

在应用里，你可以从三个地方把一段文本一键送入批判镜：

- **录音**：录音完成、端侧转写得到你本人的话语后，可在录音页选择把这段转写送入批判镜；
- **洞察**：在某条知识洞察的详情页，可以把这段洞察内容送入批判镜；
- **笔记**：在笔记编辑器中，可以把当前笔记内容送入批判镜。

三个入口都只做同一件事：把待分析文本、来源标题、来源类型标签（录音 / 笔记 / 洞察）暂存为一次「移交」。

### 由你主动触发，不自动分析

- 送入动作只在你主动点击时发生。应用不会监听你的录音、笔记或洞察，也不会在后台自动把任何内容送进镜鉴；
- 移交是纯内存、进程内的一次暂存：它不写数据库、不做内容判定、不做关键词扫描；
- 进入批判镜页时，应用取出这次移交并随即清空，文本预载到输入框里，同时标注它来自录音、笔记还是洞察；
- **它不会自动开始分析**。是否分析、用端侧模型还是你配置的云端模型，由你在批判镜页手动点击「开始分析」决定。你可以在点击前修改、删除这段预载文本。

### 为什么这样设计

镜鉴处理的是你最私密的自言自语。把「送入」和「开始分析」拆成两步、且都由你亲手触发，是为了让每一次分析都经过你的确认：你随时能在进入镜鉴后、按下分析前改变主意。来源标签则保证日后回看时，你知道这段话是从哪里来的。

### 数据与隐私

- 移交本身不落盘，应用重启或进程结束后暂存即失效；真正被分析的内容仅在你按下「开始分析」后，按你选择的模型路径处理；
- 未配置云端时，分析在端侧完成，文本不出设备；
- 云端启用后，分析所需文本由设备直接发往你选定的第三方端点，Opedrgent 不经自有服务器中转，也不对第三方的数据处理作承诺。详见 [云端服务说明](Cloud-Services.md)。

---

## English

### Three entry points

From anywhere in the app, you can send a piece of text to the Critique Mirror in one tap:

- **Recording**: once a recording finishes and the on-device transcription of your own speech is ready, you can send that transcript from the recording screen.
- **Insight**: on the detail page of a knowledge insight, you can send that insight text to the mirror.
- **Note**: inside the note editor, you can send the current note's content to the mirror.

All three entry points do the same single thing: they hand off the text to analyze, together with a source title and a source-type label (recording / note / insight).

### Triggered by you; never analyzed automatically

- A hand-off happens only when you tap it yourself. The app does not monitor your recordings, notes, or insights, and never sends anything into the mirror in the background.
- The hand-off lives purely in memory for the lifetime of the process: it writes nothing to the database, makes no content judgments, and runs no keyword scans.
- When you open the mirror screen, the app takes the hand-off and immediately clears it; the text is preloaded into the input box, marked as coming from a recording, a note, or an insight.
- **It does not start analysis automatically.** Whether to analyze at all, and with the on-device model or a cloud model you configured, is decided when you manually tap "Start analysis" on the mirror screen. You can edit or delete the preloaded text before doing so.

### Why it is designed this way

The mirror handles your most private inner speech. Splitting "send to mirror" and "start analysis" into two steps — both triggered by your own hand — means every analysis passes through your confirmation: after arriving in the mirror, and before pressing analyze, you can still change your mind. The source label keeps you able, on later review, to tell where these words came from.

### Data and privacy

- The hand-off itself never touches disk; once the app restarts or the process ends, the staged text is gone. Only after you press "Start analysis" is the content actually processed, along the model path you chose.
- Without cloud configuration, analysis happens on-device and the text never leaves the device.
- Once cloud is enabled, the text needed for analysis goes directly from your device to the third-party endpoint you chose. Opedrgent relays nothing through its own servers and makes no promises about third-party data handling. See [Cloud Services](Cloud-Services.md).

---

## 日本語

### 3つの入口

アプリ内のどこからでも、ワンタップで文章を批判鏡へ送れます。

- **録音**: 録音が終わり、端末内で自分の発話の書き起こしができたあと、録音ページからその文章を批判鏡へ送る選択ができます。
- **洞察**: ナレッジ洞察の詳細ページで、その洞察の文章を批判鏡へ送れます。
- **ノート**: ノートエディタ上で、現在のノート内容を批判鏡へ送れます。

3つの入口は同じ処理だけを行います。分析対象の文章・出どころのタイトル・出どころ種別ラベル（録音／ノート／洞察）を、1回分の「引き継ぎ」として一時保存します。

### 自分で操作して初めて発動。自動分析はしない

- 送る操作は、あなたが自分でタップしたときだけ発生します。アプリは録音・ノート・洞察を監視せず、バックグラウンドで何かを自動的に鏡鑑へ送り込むことはありません。
- 引き継ぎはプロセスメモリ内だけの一時保存です。DBに書き込まず、内容判定もせず、キーワードスキャンも行いません。
- 批判鏡のページを開いたとき、アプリはこの引き継ぎを取り出して即座に消去し、文章を入力欄に予備読み込みして、録音・ノート・洞察のどこから来たかを明示します。
- **自動で分析は始まりません。** 分析するかどうか、端末モデルと設定済みクラウドモデルのどちらを使うかは、批判鏡のページで自分が「分析開始」を押して決めます。押す前なら、この予備読み込み文章を編集・削除できます。

### なぜこの設計か

鏡鑑が扱うのは、あなたの最もプライベートな内省です。「鏡へ送る」と「分析を始める」を2段階に分け、どちらも自分の手で発動させるのは、すべての分析に一度あなたの確認を通すためです。鏡のページに入ったあと、分析を押す前なら、いつでも考え直せます。出どころラベルは、後で振り返ったとき、この言葉がどこから来たかを把握しやすくします。

### データとプライバシー

- 引き継ぎ自体はディスクに残りません。アプリの再起動やプロセス終了で、一時保存は失われます。実際に分析されるのは「分析開始」を押したあと、選んだモデルの経路に沿って処理される分だけです。
- クラウド未構成なら、分析は端末内で完結し、文章は端末から出ません。
- クラウド有効後は、分析に必要な文章が端末から選んだ第三者エンドポイントへ直接送られます。Opedrgent は自前サーバーを中継せず、第三者のデータ処理についても約束しません。詳しくは[クラウドサービス説明](Cloud-Services.md)を参照してください。

---

## 相关链接 / Related links / 関連リンク

- [个人修炼模式（Cultivation）](Cultivation.md) — 批判镜的数据流与质量门
- [认知修炼](Cognitive.md) — 想法层面的镜鉴
- [行动跟进](Actions.md) — 镜鉴产出的后续闭环
- [云端服务说明](Cloud-Services.md)
- 返回 [Wiki 首页](Home.md)
