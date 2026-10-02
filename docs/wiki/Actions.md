# 行动跟进（Action Follow-ups）

> 本页说明 Opedrgent「行动跟进」清单：行动项从哪里来、有哪些状态、如何标记完成或搁置、如何回看当初的情境，以及未完成事项如何被温和地提示。所有记录默认只保存在本机；云端模型仅在你显式启用后才会被调用，数据流向与隐私口径以仓库根目录的 [PRIVACY.md](../../PRIVACY.md) 为准。

---

## 简体中文

### 它是什么

行动跟进是一张跨场景的待办清单。批判镜、榜样镜、认知修炼镜在报告中给出的「下次可以怎么做」，以及模型在对话里主动记下的约定，都会被收敛到这张清单里统一回看，而不是散落在各份报告中找不到。

它与报告里内嵌的「后续跟进」并存：报告内的跟进项服务于那份报告本身的展示与复评；行动跟进清单服务于跨场景的待办汇总与完成闭环。

### 行动项从哪里来

- **镜鉴报告产出**：批判镜、榜样镜、认知修炼镜给出的替代说法、下一步行动，可沉淀为行动项；
- **模型工具创建**：对话过程中，模型判断双方已经商定了某件要做的事时，可调用行动项工具把它直接写入清单；
- 每条行动项都会记录来源类型（如录音、笔记、洞察、复盘）、来源标题与原文摘录，镜鉴来源还会记下对应报告的编号。

工程侧不做任何关键词匹配或意图猜测，是否记录由模型结合完整语境决定。

### 状态

行动项有三种状态：

- **待处理**：刚创建，尚未完成；
- **完成**：你做完后手动标记，系统会记下完成时间；
- **搁置**：暂时不想做，又不想让它一直出现在待办里，可以先搁置，以后再捡回来。

行动项按用途分为替代说法（下次换一种表达方式）、可执行下一步、一般行动三类，仅用于整理，不影响隐私与数据范围。

### 如何标记

在行动跟进列表中直接操作即可：完成一项就标记为完成，决定暂缓就标记为搁置，搁置的项随时可以改回待处理。对话中模型也可以通过工具更新某条行动项的状态。

### 回看原始情境与复盘

每一条行动项都带着它的来源信息。点开来源标题或摘录，可以回到当初那句话、那份笔记、那次录音或那份报告，看清这条行动项是在什么情境下提出来的，再决定它是否仍然成立。镜鉴来源的行动项可以直接跳回对应的镜鉴报告，配合长期轨迹做阶段复盘。

### 未完成事项的温和提示

当清单里还存在待处理项时，首页概览会显示一张「N 项跟进待处理」的卡片，点一下即可进入清单。这是唯一的提示方式：

- 它只按状态数量计数，不分析行动项内容，也不评价你有没有做；
- 没有待处理项时这张卡片根本不会出现；
- 应用不为此发送系统推送、不做定时催办，更不会因为拖延而反复打扰。

### 数据与隐私

- 行动项保存在本机私有数据库中，随应用沙箱保护，卸载或在设置中按类别清除数据时一并删除；
- 是否动用云端模型整理或更新行动项，取决于你在设置中的显式配置。未配置云端时，相关读写全部在端侧完成；
- 云端启用后，为完成一次请求所需的数据由设备直接发往你选定的第三方端点，Opedrgent 不经自有服务器中转，也不对第三方的数据处理作承诺。详见 [云端服务说明](Cloud-Services.md)。

---

## English

### What this is

Action Follow-ups is a single cross-scene to-do list. The "what you could try next" suggestions produced by the Critique Mirror, the Exemplar Mirror, and the Cognitive Reflection Mirror, together with commitments the model jots down during a conversation, are gathered here in one place instead of being scattered across individual reports.

It lives alongside the follow-up items embedded inside each mirror report: report-level follow-ups serve that report's own display and re-review, while this list exists for cross-scene review and a closed completion loop.

### Where items come from

- **Mirror reports**: alternative phrasings and next steps suggested by the Critique, Exemplar, or Cognitive Reflection Mirror can be turned into action items.
- **Model-created via tool**: during conversation, when the model judges that both sides have agreed on something to do, it can call the action-item tool to write it directly into the list.
- Every item records its source type (such as recording, note, insight, or reflection), a source title, and a short excerpt; mirror-sourced items also carry the id of the underlying report.

The app performs no keyword matching or intent guessing; whether an item is recorded is decided by the model from the full context.

### Statuses

Each action item has one of three statuses:

- **Open**: newly created, not yet done.
- **Done**: marked complete by you; the completion timestamp is recorded.
- **Deferred**: something you do not want to do right now but also do not want clogging your to-do list; set it aside and pick it back up later.

Items are also categorized by purpose — alternative phrasing, executable next step, or general action. These categories are purely organizational.

### How to mark status

Manage items directly in the follow-up list: mark a finished item as done, defer something you want to postpone, and bring a deferred item back to open at any time. During conversation the model can also update an item's status through the tool.

### Revisiting the original context

Every action item carries its provenance. Opening the source title or excerpt takes you back to the original sentence, note, recording, or report so you can see the situation in which the suggestion arose, and judge whether it still holds. Items sourced from a mirror report can jump straight back to that report, for periodic review alongside the long-term trajectory.

### Gentle reminders for unfinished items

When open items remain, the home overview shows one card reading "N follow-ups pending"; tapping it opens the list. This is the only reminder mechanism:

- it counts by status only — it never reads item content or judges whether you have done things;
- if there are no open items, the card is not rendered at all;
- the app sends no system notifications, runs no scheduled nudges, and will not repeatedly pester you for delays.

### Data and privacy

- Action items live in a local private database protected by the app sandbox; they are removed when you uninstall the app or clear this data category in Settings.
- Whether a cloud model is used to organize or update items depends entirely on the configuration you explicitly enter in Settings. Without cloud configuration, all reads and writes happen on-device.
- Once cloud is enabled, the data needed for a request goes directly from your device to the third-party endpoint you chose; Opedrgent relays nothing through its own servers and makes no promises about third-party data handling. See [Cloud Services](Cloud-Services.md).

---

## 日本語

### どんな機能か

行動フォローアップは、場面をまたいで使う一枚のやることリストです。批判鏡・模範鏡・認知トレーニング鏡がそれぞれのレポートで示す「次にどうするか」、および対話の中でモデルが記録した約束事が、バラバラのレポートに埋もれないよう、ここに集約されて振り返れるようになります。

レポート内部に組み込まれたフォローアップ項目とは共存関係にあります。レポート内の項目はそのレポートの表示と見直しのため、このリストは場面をまたいだ整理と「やった／やらない」の完了ループのためにあります。

### 行動項目の出どころ

- **各ミラーのレポート**: 批判鏡・模範鏡・認知トレーニング鏡が示す言い換えや次の一手が、行動項目として保存できます。
- **モデルがツールで作成**: 対話の中で「両者がやるべきことを合意した」とモデルが判断したとき、行動項目ツールで直接リストに書き込むことができます。
- 各項目には出どころの種別（録音・ノート・洞察・振り返りなど）、出どころのタイトル、抜き書きが記録され、ミラー由来の項目には元のレポート番号も保持されます。

アプリ側はキーワードマッチングや意図判定を一切行いません。記録するかどうかはモデルが文脈全体から判断します。

### ステータス

行動項目には3つの状態があります。

- **未着手（待処理）**: 作成されたばかりで、まだ終わっていない状態。
- **完了**: 自分で完了をマークすると、完了時刻が記録されます。
- **保留（棚上げ）**: 今はやりたくないけれど、やることリストに残し続けたくないとき、いったん保留にでき、あとから戻せます。

用途別に「言い換え」「実行できる次の一手」「一般の行動」の3種類に分類されますが、整理のための区分で、データの範囲やプライバシーには影響しません。

### 状態の変更

フォローアップ一覧から直接操作できます。終わった項目は完了に、いったん先送りしたい項目は保留に、保留中の項目はいつでも未着手に戻せます。対話の中でモデルがツールを通じて特定項目の状態を更新することもあります。

### 元の場面と振り返り

各行動項目には出どころの情報が付いています。出どころのタイトルや抜き書きを開くと、元の発言・ノート・録音・レポートに戻り、その提案がどんな場面で生まれたかを確認し、今も有効かどうかを判断できます。ミラー由来の項目は元のレポートに直接戻れ、長期トレンドと合わせて定期的な振り返りに使えます。

### 未完了事項へのやさしい促し

未着手の項目が残っているとき、ホーム画面の概要に「N 件のフォローアップ待ち」というカードが1枚表示され、タップでリストを開けます。これが唯一の通知方法です。

- 状態の件数だけを数え、項目の中身を分析したり「やった／やらない」を評価したりしません。
- 未着手の項目がなければ、このカードはそもそも表示されません。
- アプリはシステム通知を送らず、定時リマインダーも仕掛けず、先送りを理由に何度も急かすことはありません。

### データとプライバシー

- 行動項目は端末内のプライベートDBに保存され、アプリのサンドボックスで保護されます。アンインストール時、または設定でこのデータ区分を消去したときに一緒に削除されます。
- クラウドモデルを整理・更新に使うかどうかは、設定で自分が明示的に入力した構成に従います。クラウド未構成なら、読み書きはすべて端末内で完結します。
- クラウド有効後は、リクエストに必要なデータが端末から選んだ第三者エンドポイントへ直接送られます。Opedrgent が自前サーバーを中継せず、第三者のデータ処理についても約束しません。詳しくは[クラウドサービス説明](Cloud-Services.md)を参照してください。

---

## 相关链接 / Related links / 関連リンク

- [送入批判镜](Mirror-Handoff.md) — 录音、洞察、笔记如何一键送入镜鉴
- [认知修炼](Cognitive.md) — 想法层面的镜鉴
- [云端服务说明](Cloud-Services.md) — 本地优先与云端 opt-in
- [权限用途说明](Permissions.md)
- 返回 [Wiki 首页](Home.md)
