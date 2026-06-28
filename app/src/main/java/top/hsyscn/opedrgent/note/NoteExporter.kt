package top.hsyscn.opedrgent.note

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ExportFormat {
    TXT,
    MARKDOWN,
    HTML_ARCHIVE
}

fun exportNoteToFile(note: Note, format: ExportFormat, context: Context): File {
    val docsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        ?: context.filesDir
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
    val safeTitle = note.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(50).ifBlank { "未命名" }
    val extension = when (format) {
        ExportFormat.TXT -> "txt"
        ExportFormat.MARKDOWN -> "md"
        ExportFormat.HTML_ARCHIVE -> throw IllegalArgumentException("HTML_ARCHIVE export requires exportHtmlArchive()")
    }
    val fileName = "${safeTitle}_$timestamp.$extension"
    val file = File(docsDir, fileName)

    val content = when (format) {
        ExportFormat.TXT -> buildString {
            appendLine("标题: ${note.title}")
            appendLine(
                "时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(note.updatedAt))}"
            )
            appendLine("类型: ${note.type.displayName()}")
            appendLine("---")
            appendLine(note.content)
        }

        ExportFormat.MARKDOWN -> buildString {
            appendLine("---")
            appendLine("title: \"${note.title}\"")
            appendLine(
                "date: \"${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(note.updatedAt))}\""
            )
            appendLine("type: \"${note.type.name}\"")
            appendLine("---")
            appendLine()
            appendLine(note.content)
        }

        else -> ""
    }

    file.writeText(content)
    return file
}

/**
 * 增强型 HTML 归档导出。
 *
 * 生成一个完整的、可独立浏览的 HTML 归档包，
 * 包含仪表盘索引页 + 每篇笔记详情页 + 共享资源，
 * 设计质量全面超越 GetNotes 的导出格式。
 *
 * 文件结构:
 * opedrgent-export-{timestamp}/
 *   index.html          -- 仪表盘首页（搜索+统计+时间线+知识图谱）
 *   notes/
 *     {uuid}.html       -- 单篇笔记详情页
 *   assets/
 *     style.css          -- 共享样式 (Apple 设计语言)
 *     app.js             -- 共享逻辑（搜索、动画、暗色模式切换）
 */
suspend fun exportHtmlArchive(
    notes: List<Note>,
    context: Context,
    outputDir: File? = null,
): File = withContext(Dispatchers.IO) {
    val baseDir = outputDir ?: run {
        val docsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        File(docsDir, "opedrgent-export-$timestamp")
    }

    val assetsDir = File(baseDir, "assets").apply { mkdirs() }
    val notesDir = File(baseDir, "notes").apply { mkdirs() }

    // 1. 生成共享资源
    File(assetsDir, "style.css").writeText(generateStyleCss())
    File(assetsDir, "app.js").writeText(generateAppJs())

    // 2. 为每篇笔记生成 UUID 和详情页
    val noteMetaList = mutableListOf<JSONObject>()
    for (note in notes) {
        val uuid = UUID.randomUUID().toString().replace("-", "")
        val meta = buildNoteMeta(note, uuid)
        noteMetaList.add(meta)

        val detailHtml = generateNoteDetailHtml(note, uuid)
        File(notesDir, "$uuid.html").writeText(detailHtml)
    }

    // 3. 生成仪表盘索引页
    val indexHtml = generateIndexHtml(notes, noteMetaList)
    File(baseDir, "index.html").writeText(indexHtml)

    baseDir
}

// ---------------------------------------------------------------------------
// 内部：构建笔记元数据 JSON 对象
// ---------------------------------------------------------------------------
private fun buildNoteMeta(note: Note, uuid: String): JSONObject {
    return JSONObject().apply {
        put("uuid", uuid)
        put("id", note.id)
        put("title", note.title.ifBlank { "未命名" })
        put("summary", note.summary.take(200))
        put("type", note.type.name)
        put("typeDisplay", note.type.displayName())
        put("tags", JSONArray(note.getTags()))
        put("isPinned", note.isPinned)
        put("createdAt", note.createdAt)
        put("updatedAt", note.updatedAt)
        put("wordCount", note.wordCount)
        put("hasSprout", note.hasSproutReport())
        put("hasSourceUrl", note.sourceUrl.isNotBlank())
        put("sourceType", note.sourceType.name)
        put("hasAudio", !note.sourceUri.isNullOrBlank())
    }
}

// ---------------------------------------------------------------------------
// 内部：生成 index.html 仪表盘页
// ---------------------------------------------------------------------------
private fun generateIndexHtml(notes: List<Note>, noteMetaList: List<JSONObject>): String {
    val totalNotes = notes.size
    val totalWords = notes.sumOf { it.wordCount }
    val sproutCount = notes.count { it.hasSproutReport() }
    val allTags = mutableMapOf<String, Int>()
    notes.forEach { note ->
        note.getTags().forEach { tag ->
            allTags[tag] = (allTags[tag] ?: 0) + 1
        }
    }
    val exportTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

    // 知识图谱节点（基于标签共现，简化版）
    val graphNodes = mutableListOf<JSONObject>()
    val graphEdges = mutableListOf<JSONArray>()
    val tagNodeMap = mutableMapOf<String, Int>()
    var nodeId = 0
    allTags.entries.sortedByDescending { it.value }.take(20).forEach { (tag, count) ->
        tagNodeMap[tag] = nodeId
        graphNodes.add(JSONObject().apply {
            put("id", nodeId)
            put("label", tag)
            put("size", count)
        })
        nodeId++
    }
    // 基于笔记标签共现创建边
    notes.forEach { note ->
        val tags = note.getTags().filter { it in tagNodeMap }
        for (i in tags.indices) {
            for (j in i + 1 until tags.size) {
                val edge = JSONArray().apply {
                    put(tagNodeMap[tags[i]])
                    put(tagNodeMap[tags[j]])
                }
                graphEdges.add(edge)
            }
        }
    }

    val metaJsonArray = JSONArray(noteMetaList)

    val graphSection = if (graphNodes.size > 1) """
  <section class="kg-section reveal">
    <h2 class="section-title">知识关联图谱</h2>
    <p class="section-desc">基于标签共现关系构建的笔记关联网络</p>
    <div class="graph-wrapper">
      <canvas id="kg-canvas" width="800" height="400"></canvas>
    </div>
  </section>
  """ else ""

    return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Opedrgent 笔记归档</title>
<link rel="stylesheet" href="assets/style.css">
</head>
<body>
<div class="bg-orbs">
  <div class="orb orb-1"></div>
  <div class="orb orb-2"></div>
  <div class="orb orb-3"></div>
</div>

<div class="app-container">

  <header class="top-nav">
    <div class="nav-brand">
      <span class="brand-icon">O</span>
      <span class="brand-text">Opedrgent</span>
      <span class="brand-sub">笔记归档</span>
    </div>
    <div class="nav-actions">
      <button id="theme-toggle" class="btn-icon" title="切换深色/浅色模式" aria-label="切换主题">
        <svg class="icon-sun" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/></svg>
        <svg class="icon-moon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="display:none"><path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z"/></svg>
      </button>
    </div>
  </header>

  <section class="hero-section reveal">
    <div class="hero-content">
      <h1 class="hero-title">笔记归档</h1>
      <p class="hero-subtitle">导出时间: $exportTime</p>
    </div>
  </section>

  <section class="stats-row reveal">
    <div class="stat-card">
      <div class="stat-number">$totalNotes</div>
      <div class="stat-label">总笔记数</div>
    </div>
    <div class="stat-card">
      <div class="stat-number">${String.format("%,d", totalWords)}</div>
      <div class="stat-label">总字数</div>
    </div>
    <div class="stat-card">
      <div class="stat-number">$sproutCount</div>
      <div class="stat-label">发芽报告</div>
    </div>
    <div class="stat-card">
      <div class="stat-number">${allTags.size}</div>
      <div class="stat-label">标签数</div>
    </div>
  </section>

  <section class="search-section reveal">
    <div class="search-bar">
      <svg class="search-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/></svg>
      <input type="text" id="global-search" placeholder="搜索标题 / 内容 / 标签..." autocomplete="off">
      <span id="search-count" class="search-count"></span>
    </div>
  </section>

  <section class="view-switcher reveal">
    <button class="view-btn active" data-view="list" aria-pressed="true">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
      列表
    </button>
    <button class="view-btn" data-view="timeline" aria-pressed="false">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
      时间线
    </button>
    <button class="view-btn" data-view="tags" aria-pressed="false">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20.59 13.41l-7.17 7.17a2 2 0 01-2.83 0L2 12V2h10l8.59 8.59a2 2 0 010 2.82z"/><line x1="7" y1="7" x2="7.01" y2="7"/></svg>
      标签云
    </button>
  </section>

  <main class="main-content">

    <div id="view-list" class="view-panel active">
      <div id="notes-list" class="notes-list"></div>
      <div id="no-results" class="no-results" style="display:none;">
        <p>没有找到匹配的笔记</p>
      </div>
    </div>

    <div id="view-timeline" class="view-panel">
      <div id="timeline-container" class="timeline-container"></div>
    </div>

    <div id="view-tags" class="view-panel">
      <div id="tag-cloud" class="tag-cloud"></div>
    </div>

  </main>

  $graphSection

  <footer class="page-footer">
    <p>Opedrgent 笔记导出 &middot; 共 $totalNotes 条笔记 &middot; $exportTime</p>
  </footer>

</div>

<script id="notes-data" type="application/json">
$metaJsonArray
</script>

<script id="graph-data" type="application/json">
{
  "nodes": ${JSONArray(graphNodes)},
  "edges": ${JSONArray(graphEdges)}
}
</script>

<script src="assets/app.js"></script>
<!-- Liquid Glass Optical Refraction Filter -->
<svg xmlns="http://www.w3.org/2000/svg" width="0" height="0" style="position:fixed;top:0;left:0;pointer-events:none;z-index:9998">
  <defs>
    <filter id="lg-refraction" filterUnits="userSpaceOnUse" colorInterpolationFilters="sRGB" x="0" y="0" width="400" height="300">
      <feImage id="lg-displacement-map" width="400" height="300"/>
      <feDisplacementMap in="SourceGraphic" in2="lg-displacement-map" xChannelSelector="R" yChannelSelector="G" scale="30"/>
    </filter>
  </defs>
</svg>
</body>
</html>"""
}

// ---------------------------------------------------------------------------
// 内部：生成单篇笔记详情页
// ---------------------------------------------------------------------------

/** Convert Compose Color to CSS hex string for inline styles */
private fun NoteType.colorToCssHex(): String {
    val color = this.color()
    return String.format("#%02X%02X%02X",
        (color.red * 255).toInt().coerceIn(0, 255),
        (color.green * 255).toInt().coerceIn(0, 255),
        (color.blue * 255).toInt().coerceIn(0, 255)
    )
}

/** Format word count as a fake duration for audio notes (approx: 3 chars/sec speech rate) */
private fun formatDurationForAudio(wordCount: Int): String {
    val totalSeconds = (wordCount / 3).coerceAtLeast(1)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun generateNoteDetailHtml(note: Note, uuid: String): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    val createdStr = sdf.format(Date(note.createdAt))
    val updatedStr = sdf.format(Date(note.updatedAt))
    val safeTitle = escapeHtml(note.title.ifBlank { "\u672a\u547d\u540d" })

    val sproutArticle = note.getSproutArticle()
    val sproutSection = if (sproutArticle != null) {
        val article = sproutArticle
        val sectionsHtml = article.articles.mapIndexed { _, sec ->
            val importanceStars = "*".repeat(sec.importance.coerceIn(1, 5))
            val shockingHtml = if (sec.shockingMoment.isNotBlank()) {
                "\n              <div class=\"aha-moment\">\n" +
                "                <span class=\"aha-label\">震惊瞬间</span>\n" +
                "                <blockquote class=\"aha-quote\">${escapeHtml(sec.shockingMoment)}</blockquote>\n" +
                "              </div>"
            } else ""
            """
            <article class="sprout-article">
              <h3 class="sprout-article-title">${escapeHtml(sec.title)}</h3>
              <div class="sprout-seed">
                <span class="seed-label">种子</span>
                <blockquote class="seed-quote">${escapeHtml(sec.seed)}</blockquote>
              </div>
              <div class="sprout-body">
                ${renderMarkdownSimple(sec.body)}
              </div>
              $shockingHtml
              <div class="sprout-importance">
                重要度: <span class="stars">$importanceStars</span>
              </div>
            </article>
            """.trimIndent()
        }.joinToString("\n")

        val actionItemsHtml = if (article.actionItems.isNotEmpty()) {
            val items = article.actionItems.joinToString("\n") {
                "<li><label><input type=\"checkbox\"> <span>${escapeHtml(it)}</span></label></li>"
            }
            "\n        <div class=\"action-items-section\">\n" +
            "          <h4 class=\"action-title\">行动建议</h4>\n" +
            "          <ul class=\"action-list\">\n$items\n" +
            "          </ul>\n" +
            "        </div>"
        } else ""

        val conceptsHtml = if (article.relatedConcepts.isNotEmpty()) {
            val chips = article.relatedConcepts.joinToString("") {
                "<span class=\"concept-chip\">${escapeHtml(it)}</span>"
            }
            "\n        <div class=\"concepts-section\">\n" +
            "          <span class=\"concepts-label\">相关概念</span>\n" +
            "          <div class=\"concepts-chips\">$chips</div>\n" +
            "        </div>"
        } else ""

        val sentimentLabel = when (article.sentiment) {
            Sentiment.POSITIVE -> "积极"
            Sentiment.NEGATIVE -> "消极"
            Sentiment.MIXED -> "混合"
            else -> "中性"
        }
        val generatedStr = sdf.format(Date(article.generatedAt))
        val summaryLine = if (article.summary.isNotBlank()) "\n            <p class=\"sprout-summary\">${escapeHtml(article.summary)}</p>" else ""

        "\n        <section class=\"sprout-report-section\">\n" +
        "          <div class=\"sprout-header glass-card\">\n" +
        "            <h2 class=\"sprout-main-title\">AI 发芽报告</h2>\n" +
        "            <div class=\"sprout-meta-row\">\n" +
        "              <span class=\"sprout-meta-item\">模型: ${escapeHtml(article.modelUsed)}</span>\n" +
        "              <span class=\"sprout-meta-item\">情感: $sentimentLabel</span>\n" +
        "              <span class=\"sprout-meta-item\">阅读: 约 ${article.readingTimeMinutes} 分钟</span>\n" +
        "              <span class=\"sprout-meta-item\">生成于: $generatedStr</span>\n" +
        "            </div>$summaryLine\n" +
        "          </div>\n\n" +
        "          <div class=\"sprout-articles\">\n$sectionsHtml\n" +
        "          </div>\n" +
        "$actionItemsHtml$conceptsHtml\n" +
        "        </section>"
    } else ""

    // --- Three-layer tag system ---
    val typeColorHex = note.type.colorToCssHex()
    val userTags = note.getTags()
    val hasSprout = note.hasSproutReport()

    val tagRowHtml = buildString {
        appendLine("    <div class=\"tag-row\">")
        // Layer 1: System tag (note type)
        appendLine("      <span class=\"tag-pill system type-${note.type.name.lowercase()}\" style=\"--tag-system-color:$typeColorHex\">${escapeHtml(note.type.displayName())}</span>")
        // Layer 2: User tags
        userTags.forEach { tag ->
            appendLine("      <span class=\"tag-pill user\">${escapeHtml(tag)}</span>")
        }
        // Layer 3: AI tag (if has sprout report)
        if (hasSprout) {
            appendLine("      <span class=\"tag-pill ai\">AI \u53d1\u82bd\u5c31\u7eea</span>")
        }
        appendLine("    </div>")
    }

    // --- Waveform player (only for audio-type notes) ---
    val isAudioType = note.type == NoteType.ASR || note.type == NoteType.AUDIO || note.type == NoteType.MEETING
    val formattedDuration = formatDurationForAudio(note.wordCount)
    val waveformSection = if (isAudioType) """
    <div class="waveform-player" id="waveformPlayer">
      <button class="play-btn" id="playBtn" onclick="togglePlay()" aria-label="\u64ad\u653e/\u6682\u505c">
        <svg viewBox="0 0 24 24"><polygon points="6,3 20,12 6,21"/></svg>
      </button>
      <div class="waveform-container" onclick="seekWave(event)" id="waveContainer">
        <div class="w-bars" id="waveform"></div>
        <div class="progress-line" id="progress"></div>
      </div>
      <span class="duration" id="audioDuration">$formattedDuration</span>
    </div>
    """ else ""

    // --- Tab bar + 4 panels ---
    val tabContentBody = renderMarkdownSimple(note.content)

    val originalContentHtml = if (note.sourceUrl.isNotBlank() || !note.originalContent.isNullOrBlank()) {
        val urlPart = if (note.sourceUrl.isNotBlank()) """
          <div class="quote-card">
            <div class="quote-card-label">\u94fe\u63a5\u539f\u6587</div>
            <a href="${escapeAttr(note.sourceUrl)}" target="_blank" rel="noopener noreferrer" style="color:var(--accent);font-size:14px;word-break:break-all;text-decoration:none;position:relative;z-index:2;">${escapeHtml(note.sourceUrl)}</a>
          </div>
        """ else ""
        val origContent = note.originalContent
        val origPart = if (!origContent.isNullOrBlank()) """
          <div class="quote-card">
            <div class="quote-card-label">\u539f\u59cb\u5185\u5bb9</div>
            <div class="quote-card-body">${renderMarkdownSimple(origContent)}</div>
          </div>
        """ else ""
        "$urlPart$origPart"
    } else """<div class="quote-card-empty">\u6682\u65e0\u94fe\u63a5\u539f\u6587</div>"""

    val sproutTabContent = if (hasSprout) sproutSection else """<div class="quote-card-empty">AI \u6b63\u5728\u5206\u6790\u4e2d...</div>"""

    return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>$safeTitle - Opedrgent 笔记</title>
<link rel="stylesheet" href="../assets/style.css">
</head>
<body>
<div class="bg-orbs">
  <div class="orb orb-1"></div>
  <div class="orb orb-2"></div>
  <div class="orb orb-3"></div>
</div>

<div class="app-container detail-page">

  <nav class="breadcrumb reveal">
    <a href="../index.html" class="breadcrumb-link">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>
      返回归档首页
    </a>
    <span class="breadcrumb-sep">/</span>
    <span class="breadcrumb-current">$safeTitle</span>
  </nav>

  <header class="detail-header glass-card reveal">
    <div class="detail-title-row">
      <h1 class="detail-title">$safeTitle</h1>
      ${if (note.isPinned) "<span class=\"pin-badge\">\u7f6e\u9876</span>" else ""}
    </div>
    <div class="detail-meta">
      <span class="meta-chip source-chip">${note.sourceType.name.replace("_", " ")}</span>
      <span class="meta-chip time-chip">\u521b\u5efa\u4e8e $createdStr</span>
      <span class="meta-chip time-chip">\u66f4\u65b0\u4e8e $updatedStr</span>
      <span class="meta-chip word-chip">${note.wordCount} \u5b57</span>
    </div>
    $tagRowHtml
  </header>

  $waveformSection

  <!-- Tab Navigation Bar -->
  <div class="tab-bar reveal" id="tabBar">
    <button class="tab-pill active" data-tab="content">\u7b14\u8bb0\u5185\u5bb9</button>
    <button class="tab-pill" data-tab="original">\u94fe\u63a5\u539f\u6587</button>
    <button class="tab-pill" data-tab="append">\u8ffd\u52a0\u7b14\u8bb0</button>
    <button class="tab-pill" data-tab="sprout">\u53d1\u82bd\u62a5\u544a</button>
  </div>

  <!-- Tab Panel: Content -->
  <div id="tab-content" class="tab-content active">
    <article class="detail-content glass-card reveal">
      <div class="markdown-body" id="markdownBody">
        $tabContentBody
      </div>
    </article>
    <!-- Chapter list will be injected here by JS if timestamps found -->
    <div id="chapterContainer"></div>
  </div>

  <!-- Tab Panel: Original -->
  <div id="tab-original" class="tab-content">
    $originalContentHtml
  </div>

  <!-- Tab Panel: Append -->
  <div id="tab-append" class="tab-content">
    <div class="quote-card-empty">\u6682\u65e0\u8ffd\u52a0\u7b14\u8bb0</div>
  </div>

  <!-- Tab Panel: Sprout -->
  <div id="tab-sprout" class="tab-content">
    $sproutTabContent
  </div>

</div>

<script src="../assets/app.js"></script>
<!-- Liquid Glass Optical Refraction Filter -->
<svg xmlns="http://www.w3.org/2000/svg" width="0" height="0" style="position:fixed;top:0;left:0;pointer-events:none;z-index:9998">
  <defs>
    <filter id="lg-refraction" filterUnits="userSpaceOnUse" colorInterpolationFilters="sRGB" x="0" y="0" width="400" height="300">
      <feImage id="lg-displacement-map" width="400" height="300"/>
      <feDisplacementMap in="SourceGraphic" in2="lg-displacement-map" xChannelSelector="R" yChannelSelector="G" scale="30"/>
    </filter>
  </defs>
</svg>
</body>
</html>"""
}

// ---------------------------------------------------------------------------
// 内部：生成 style.css (Apple 设计语言)
// ---------------------------------------------------------------------------
private fun generateStyleCss(): String {
    return """/*
 * Opedrgent Note Archive - Apple Liquid Glass Design Language (iOS 26 / WWDC 2025)
 */

/* ====== CSS Custom Properties (Design Tokens) ====== */
:root {
  /* Surface */
  --glass-bg: rgba(255, 255, 255, 0.18);
  --glass-bg-hover: rgba(255, 255, 255, 0.28);
  --glass-bg-solid: rgba(255, 255, 255, 0.55);
  --glass-border: rgba(255, 255, 255, 0.28);
  --glass-border-hover: rgba(255, 255, 255, 0.45);
  --glass-shadow-outer: 0 8px 32px rgba(0, 0, 0, 0.06), inset 0 1px 2px rgba(255, 255, 255, 0.5), inset 0 -1px 2px rgba(0, 0, 0, 0.04);
  --glass-shadow-hover: 0 12px 40px rgba(0, 0, 0, 0.10), inset 0 1px 2px rgba(255, 255, 255, 0.6), inset 0 -1px 2px rgba(0, 0, 0, 0.06);
  --glass-blur: blur(24px) saturate(180%) brightness(1.1);

  /* Background */
  --bg-primary: #f2f2f7;
  --bg-secondary: #ffffff;
  --bg-gradient-start: #e8e8ed;
  --bg-gradient-end: #f5f5fa;

  /* Text */
  --text-primary: #1c1c1e;
  --text-secondary: #636366;
  --text-tertiary: #aeaeb2;
  --text-on-accent: #ffffff;

  /* Accent Palette (Apple Official) */
  --accent-blue: #007AFF;
  --accent-purple: #AF52DE;
  --accent-cyan: #32ADE6;
  --accent-green: #34C759;
  --accent-orange: #FF9F0A;
  --accent-red: #FF3B30;
  --accent-pink: #FF2D55;
  --accent-indigo: #5856D6;
  --accent: var(--accent-blue);
  --accent-light: rgba(0, 122, 255, 0.12);
  --accent-glow: rgba(0, 122, 255, 0.20);

  /* Radius */
  --radius-sm: 12px;
  --radius-md: 18px;
  --radius-lg: 24px;
  --radius-xl: 28px;

  /* Typography */
  --font-stack: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'SF Pro Text', 'Helvetica Neue', 'PingFang SC', 'Noto Sans SC', sans-serif;
  --mono-stack: 'SF Mono', SFMono-Regular, Menlo, Monaco, Consolas, monospace;

  /* Spring Animation (Liquid Glass Physics) */
  --spring-smooth: 0.5s cubic-bezier(0.34, 1.56, 0.64, 1);
  --spring-snappy: 0.35s cubic-bezier(0.34, 1.56, 0.64, 1);
  --ease-standard: 0.25s cubic-bezier(0.25, 0.46, 0.45, 0.94);

  /* Type Colors */
  --color-text: var(--accent-blue);
  --color-asr: #E67E22;
  --color-meeting: #9B59B6;
  --color-link: #3498DB;
  --color-quick: #F39C12;
  --color-aichat: #1ABC9C;
  --color-image: #2ECC71;
  --color-pdf: #E74C3C;
  --color-audio: var(--accent-purple);
  --color-book: #34495E;

  /* Sprout */
  --sprout-bg: linear-gradient(135deg, rgba(240,253,244,0.7) 0%, rgba(236,253,245,0.6) 50%, rgba(240,249,255,0.7) 100%);
  --aha-border-left: #F59E0B;
  --aha-bg: rgba(245, 158, 11, 0.08);
  --seed-text: #6B7280;

  /* Refraction Highlight Opacity */
  --refraction-light: 0.22;
}

/* ====== Dark Mode Liquid Glass ====== */
@media (prefers-color-scheme: dark) {
  :root {
    --glass-bg: rgba(30, 30, 32, 0.55);
    --glass-bg-hover: rgba(44, 44, 46, 0.68);
    --glass-bg-solid: rgba(44, 44, 46, 0.80);
    --glass-border: rgba(255, 255, 255, 0.12);
    --glass-border-hover: rgba(255, 255, 255, 0.20);
    --glass-shadow-outer: 0 8px 32px rgba(0, 0, 0, 0.20), inset 0 1px 2px rgba(255, 255, 255, 0.08), inset 0 -1px 2px rgba(0, 0, 0, 0.15);
    --glass-shadow-hover: 0 12px 40px rgba(0, 0, 0, 0.30), inset 0 1px 2px rgba(255, 255, 255, 0.10), inset 0 -1px 2px rgba(0, 0, 0, 0.18);
    --glass-blur: blur(28px) saturate(160%) brightness(1.15);

    --bg-primary: #000000;
    --bg-secondary: #1c1c1e;
    --bg-gradient-start: #0a0a0c;
    --bg-gradient-end: #141416;

    --text-primary: #f5f5f7;
    --text-secondary: #98989d;
    --text-tertiary: #636366;

    --accent: var(--accent-cyan);
    --accent-light: rgba(50, 173, 230, 0.14);
    --accent-glow: rgba(50, 173, 230, 0.22);

    --sprout-bg: linear-gradient(135deg, rgba(5,46,22,0.7) 0%, rgba(12,45,45,0.6) 50%, rgba(12,25,41,0.7) 100%);
    --aha-border-left: #F59E0B;
    --aha-bg: rgba(245, 158, 11, 0.08);
    --seed-text: #9CA3AF;

    --refraction-light: 0.10;
  }
}

/* ====== Manual Theme Override Classes ====== */
body.light-mode {
  --glass-bg: rgba(255,255,255,0.18); --glass-bg-hover: rgba(255,255,255,0.28); --glass-bg-solid: rgba(255,255,255,0.55);
  --glass-border: rgba(255,255,255,0.28); --glass-border-hover: rgba(255,255,255,0.45);
  --glass-shadow-outer: 0 8px 32px rgba(0,0,0,0.06),inset 0 1px 2px rgba(255,255,255,0.5),inset 0 -1px 2px rgba(0,0,0,0.04);
  --glass-blur: blur(24px)saturate(180%)brightness(1.1);
  --bg-primary:#f2f2f7;--bg-secondary:#ffffff;--bg-gradient-start:#e8e8ed;--bg-gradient-end:#f5f5fa;
  --text-primary:#1c1c1e;--text-secondary:#636366;--text-tertiary:#aeaeb2;
  --accent:#007AFF;--accent-light:rgba(0,122,255,0.12);--accent-glow:rgba(0,122,255,0.20);
  --sprout-bg:linear-gradient(135deg,rgba(240,253,244,0.7)0%,rgba(236,253,245,0.6)50%,rgba(240,249,255,0.7)100%);
  --aha-border-left:#F59E0B;--aha-bg:rgba(245,158,11,0.08);--seed-text:#6B7280;
  --refraction-light:0.22;
}
body.dark-mode {
  --glass-bg:rgba(30,30,32,0.55);--glass-bg-hover:rgba(44,44,46,0.68);--glass-bg-solid:rgba(44,44,46,0.80);
  --glass-border:rgba(255,255,255,0.12);--glass-border-hover:rgba(255,255,255,0.20);
  --glass-shadow-outer:0 8px 32px rgba(0,0,0,0.20),inset 0 1px 2px rgba(255,255,255,0.08),inset 0 -1px 2px rgba(0,0,0,0.15);
  --glass-blur:blur(28px)saturate(160%)brightness(1.15);
  --bg-primary:#000000;--bg-secondary:#1c1c1e;--bg-gradient-start:#0a0a0c;--bg-gradient-end:#141416;
  --text-primary:#f5f5f7;--text-secondary:#98989d;--text-tertiary:#636366;
  --accent:#32ADE6;--accent-light:rgba(50,173,230,0.14);--accent-glow:rgba(50,173,230,0.22);
  --sprout-bg:linear-gradient(135deg,rgba(5,46,22,0.7)0%,rgba(12,45,45,0.6)50%,rgba(12,25,41,0.7)100%);
  --aha-border-left:#F59E0B;--aha-bg:rgba(245,158,11,0.08);--seed-text:#9CA3AF;
  --refraction-light:0.10;
}

/* ====== Reset + Base ====== */
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
html { scroll-behavior: smooth; -webkit-text-size-adjust: 100%; }
body {
  font-family: var(--font-stack);
  background: linear-gradient(160deg, var(--bg-gradient-start) 0%, var(--bg-gradient-end) 50%, var(--bg-primary) 100%);
  background-attachment: fixed;
  color: var(--text-primary);
  line-height: 1.65;
  min-height: 100vh;
  overflow-x: hidden;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}
::selection { background: var(--accent); color: var(--text-on-accent); }

/* ====== Animated Background Orbs ====== */
.bg-orbs { position: fixed; inset: 0; pointer-events: none; z-index: 0; overflow: hidden; }
.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.25;
  animation: orbFloat 24s ease-in-out infinite alternate;
}
.orb-1 {
  width: 520px; height: 520px;
  background: radial-gradient(circle, var(--accent-blue) 0%, transparent 70%);
  top: -12%; left: -8%;
  animation-delay: 0s;
}
.orb-2 {
  width: 420px; height: 420px;
  background: radial-gradient(circle, var(--accent-purple) 0%, transparent 70%);
  bottom: -12%; right: -8%;
  animation-delay: -8s;
}
.orb-3 {
  width: 380px; height: 380px;
  background: radial-gradient(circle, var(--accent-cyan) 0%, transparent 70%);
  top: 42%; right: 18%;
  animation-delay: -16s;
}
@keyframes orbFloat {
  0%   { transform: translate(0, 0) scale(1) rotate(0deg); }
  33%  { transform: translate(50px, -35px) scale(1.10) rotate(3deg); }
  66%  { transform: translate(-25px, 25px) scale(0.94) rotate(-2deg); }
  100% { transform: translate(18px, 12px) scale(1.04) rotate(1deg); }
}
@media (prefers-reduced-motion: reduce) {
  .orb { animation: none; opacity: 0.10; }
  .reveal { transition: none !important; opacity: 1 !important; transform: none !important; }
}

/* ====== App Container ====== */
.app-container { position: relative; z-index: 1; max-width: 960px; margin: 0 auto; padding: 0 24px 60px; }
.detail-page { max-width: 800px; }

/* ====== Liquid Glass Material Base ====== */
.glass-card {
  position: relative;
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  box-shadow: var(--glass-shadow-overflow, var(--glass-shadow-outer));
  transition:
    box-shadow var(--spring-smooth),
    border-color var(--spring-smooth),
    background var(--spring-smooth),
    transform var(--spring-smooth);
  overflow: hidden;
}
.glass-card::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 50%;
  border-radius: inherit;
  background: linear-gradient(
    135deg,
    rgba(255, 255, 255, var(--refraction-light)) 0%,
    rgba(255, 255, 255, 0.04) 50%,
    transparent 100%
  );
  pointer-events: none;
  z-index: 1;
}
.glass-card:hover {
  border-color: var(--glass-border-hover);
  box-shadow: var(--glass-shadow-hover);
  background: var(--glass-bg-hover);
}

/* ====== Top Navigation (Liquid Glass Bar) ====== */
.top-nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 0;
  margin-bottom: 36px;
  position: relative;
}
.top-nav::after {
  content: '';
  position: absolute;
  bottom: 0; left: -24px; right: -24px;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--glass-border), transparent);
}
.nav-brand { display: flex; align-items: center; gap: 10px; }
.brand-icon {
  display: inline-flex; align-items: center; justify-content: center;
  width: 36px; height: 36px;
  background: linear-gradient(135deg, var(--accent-blue), var(--accent-purple));
  color: #fff;
  border-radius: var(--radius-sm);
  font-weight: 700; font-size: 17px;
  box-shadow: 0 4px 12px rgba(0, 122, 255, 0.30);
  transition: transform var(--spring-smooth);
}
.brand-icon:hover { transform: scale(1.08) rotate(-3deg); }
.brand-text { font-size: 18px; font-weight: 600; letter-spacing: -0.3px; }
.brand-sub { font-size: 13px; color: var(--text-secondary); margin-left: 4px; }
.nav-actions { display: flex; align-items: center; gap: 12px; }

.btn-icon {
  display: inline-flex; align-items: center; justify-content: center;
  width: 38px; height: 38px;
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-sm);
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--spring-smooth);
  position: relative; overflow: hidden;
}
.btn-icon::before {
  content: '';
  position: absolute; inset: 0;
  background: linear-gradient(135deg, rgba(255,255,255,var(--refraction-light)) 0%, transparent 60%);
  pointer-events: none;
}
.btn-icon:hover {
  background: var(--glass-bg-hover);
  color: var(--text-primary);
  border-color: var(--glass-border-hover);
  transform: translateY(-1px);
  box-shadow: var(--glass-shadow-outer);
}
.btn-icon:active { transform: scale(0.95); }

/* ====== Hero Section ====== */
.hero-section { text-align: center; padding: 48px 0 40px; }
.hero-title {
  font-size: clamp(32px, 6vw, 48px);
  font-weight: 700;
  letter-spacing: -1.2px;
  line-height: 1.15;
  margin-bottom: 10px;
  background: linear-gradient(135deg, var(--text-primary) 0%, var(--accent) 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}
.hero-subtitle { font-size: 15px; color: var(--text-secondary); }

/* ====== Stats Row (Glass Cards) ====== */
.stats-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 14px;
  margin-bottom: 36px;
}
.stat-card {
  position: relative;
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  padding: 24px 20px;
  text-align: center;
  box-shadow: var(--glass-shadow-outer);
  transition: all var(--spring-smooth);
  overflow: hidden;
}
.stat-card::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 50%;
  border-radius: inherit;
  background: linear-gradient(
    135deg,
    rgba(255, 255, 255, var(--refraction-light)) 0%,
    rgba(255, 255, 255, 0.04) 50%,
    transparent 100%
  );
  pointer-events: none;
}
.stat-card:hover {
  transform: translateY(-4px) scale(1.02);
  box-shadow: var(--glass-shadow-hover);
  border-color: var(--glass-border-hover);
  background: var(--glass-bg-hover);
}
.stat-number {
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.8px;
  color: var(--accent);
  line-height: 1.2;
}
.stat-label { font-size: 13px; color: var(--text-secondary); margin-top: 6px; font-weight: 500; }

/* ====== Search Bar (Glass Input) ====== */
.search-section { margin-bottom: 28px; }
.search-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  padding: 14px 22px;
  box-shadow: var(--glass-shadow-outer);
  transition: all var(--spring-smooth);
  position: relative; overflow: hidden;
}
.search-bar::before {
  content: '';
  position: absolute; inset: 0;
  background: linear-gradient(135deg, rgba(255,255,255,var(--refraction-light)) 0%, transparent 60%);
  pointer-events: none;
  border-radius: inherit;
}
.search-bar:focus-within {
  border-color: var(--accent);
  box-shadow:
    var(--glass-shadow-hover),
    0 0 0 4px var(--accent-glow),
    0 0 20px var(--accent-glow);
}
.search-icon { flex-shrink: 0; color: var(--text-tertiary); position: relative; z-index: 1; }
#global-search {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  font-family: var(--font-stack);
  font-size: 15px;
  color: var(--text-primary);
  position: relative; z-index: 1;
}
#global-search::placeholder { color: var(--text-tertiary); }
.search-count { font-size: 13px; color: var(--text-tertiary); white-space: nowrap; position: relative; z-index: 1; }

/* ====== View Switcher (Segmented Control - Glass) ====== */
.view-switcher {
  display: flex;
  gap: 4px;
  margin-bottom: 28px;
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  padding: 5px;
  width: fit-content;
  box-shadow: var(--glass-shadow-outer);
  position: relative;
}
.view-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 18px;
  border: none;
  border-radius: calc(var(--radius-sm) - 2px);
  background: transparent;
  font-family: var(--font-stack);
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--spring-smooth);
  position: relative; z-index: 1;
}
.view-btn:hover { color: var(--text-primary); background: rgba(255,255,255,0.06); }
.view-btn.active {
  background: var(--accent);
  color: var(--text-on-accent);
  box-shadow: 0 2px 8px rgba(0, 122, 255, 0.35);
  font-weight: 600;
}
.view-btn.active:hover { transform: scale(1.02); }

/* ====== View Panels ====== */
.view-panel { display: none; }
.view-panel.active { display: block; }

/* ====== Note List / Table ====== */
.notes-list { display: flex; flex-direction: column; gap: 12px; }
.note-card {
  display: block;
  position: relative;
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  padding: 20px 24px;
  text-decoration: none;
  color: inherit;
  box-shadow: var(--glass-shadow-outer);
  transition: all var(--spring-smooth);
  overflow: hidden;
}
.note-card::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 50%;
  border-radius: inherit;
  background: linear-gradient(
    135deg,
    rgba(255, 255, 255, var(--refraction-light)) 0%,
    rgba(255, 255, 255, 0.04) 50%,
    transparent 100%
  );
  pointer-events: none;
  z-index: 1;
}
.note-card:hover {
  transform: translateY(-2px) scale(1.005);
  box-shadow: var(--glass-shadow-hover);
  border-color: var(--accent);
  background: var(--glass-bg-hover);
}
.note-card-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 8px; position: relative; z-index: 2; }
.note-card-title { font-size: 16px; font-weight: 600; letter-spacing: -0.2px; line-height: 1.35; flex: 1; }
.note-card-badges { display: flex; gap: 6px; flex-shrink: 0; }
.card-type-badge {
  display: inline-block;
  padding: 3px 9px;
  font-size: 11px;
  font-weight: 600;
  border-radius: 8px;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border: 1px solid var(--glass-border);
}
.card-type-badge.type-text     { background: rgba(0,122,255,0.12);   color: var(--color-text);   border-color: rgba(0,122,255,0.20); }
.card-type-badge.type-asr      { background: rgba(230,126,34,0.12);   color: var(--color-asr);    border-color: rgba(230,126,34,0.20); }
.card-type-badge.type-meeting  { background: rgba(155,89,182,0.12);   color: var(--color-meeting); border-color: rgba(155,89,182,0.20); }
.card-type-badge.type-link     { background: rgba(52,152,219,0.12);    color: var(--color-link);   border-color: rgba(52,152,219,0.20); }
.card-type-badge.type-quick    { background: rgba(243,156,18,0.12);    color: var(--color-quick);  border-color: rgba(243,156,18,0.20); }
.card-type-badge.type-aichat,
.card-type-badge.type-ai_chat  { background: rgba(26,188,156,0.12);    color: var(--color-aichat); border-color: rgba(26,188,156,0.20); }
.card-type-badge.type-image    { background: rgba(46,204,113,0.12);    color: var(--color-image);  border-color: rgba(46,204,113,0.20); }
.card-type-badge.type-pdf      { background: rgba(231,76,60,0.12);     color: var(--color-pdf);    border-color: rgba(231,76,60,0.20); }
.card-type-badge.type-audio    { background: rgba(175,82,222,0.12);    color: var(--color-audio);  border-color: rgba(175,82,222,0.20); }
.card-type-badge.type-book     { background: rgba(52,73,94,0.12);     color: var(--color-book);   border-color: rgba(52,73,94,0.20); }
.pin-indicator { display: inline-flex; align-items: center; gap: 3px; font-size: 11px; color: var(--color-quick); font-weight: 600; }
.note-card-summary { font-size: 14px; color: var(--text-secondary); line-height: 1.55; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; margin-bottom: 10px; position: relative; z-index: 2; }
.note-card-footer { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; position: relative; z-index: 2; }
.note-card-tag {
  display: inline-block;
  padding: 3px 10px;
  font-size: 12px;
  background: var(--glass-bg);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  color: var(--accent);
  border: 1px solid var(--glass-border);
  border-radius: 999px;
  font-weight: 500;
  transition: all var(--spring-smooth);
}
.note-card-tag:hover { background: var(--accent); color: var(--text-on-accent); }
.note-card-time { font-size: 12px; color: var(--text-tertiary); margin-left: auto; }
.sprout-indicator { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; font-weight: 600; color: var(--accent-green); }

/* ====== Timeline View (Glass Line + Node Cards) ====== */
.timeline-container { position: relative; padding-left: 36px; }
.timeline-container::before {
  content: '';
  position: absolute;
  left: 13px; top: 0; bottom: 0;
  width: 2px;
  background: linear-gradient(180deg, var(--accent-blue), var(--accent-purple), var(--accent-cyan));
  border-radius: 1px;
  opacity: 0.30;
  box-shadow: 0 0 8px var(--accent-glow);
}
.timeline-month-group { margin-bottom: 36px; }
.timeline-month-title {
  font-size: 14px; font-weight: 600; color: var(--text-secondary);
  margin-bottom: 14px; letter-spacing: 0.2px;
}
.timeline-item { position: relative; margin-bottom: 14px; }
.timeline-item::before {
  content: '';
  position: absolute;
  left: -29px; top: 18px;
  width: 12px; height: 12px;
  border-radius: 50%;
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 2px solid var(--accent);
  box-shadow: 0 0 8px var(--accent-glow);
}

/* ====== Tag Cloud (Glass Chips) ====== */
.tag-cloud { display: flex; flex-wrap: wrap; gap: 10px; padding: 24px 0; justify-content: center; }
.tag-cloud-item {
  display: inline-block;
  padding: 7px 18px;
  border-radius: 999px;
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  color: var(--text-primary);
  font-weight: 500;
  cursor: pointer;
  transition: all var(--spring-smooth);
  text-decoration: none;
  position: relative; overflow: hidden;
  box-shadow: var(--glass-shadow-outer);
}
.tag-cloud-item::before {
  content: '';
  position: absolute; inset: 0;
  background: linear-gradient(135deg, rgba(255,255,255,var(--refraction-light)) 0%, transparent 60%);
  pointer-events: none;
}
.tag-cloud-item:hover {
  background: var(--accent);
  color: var(--text-on-accent);
  border-color: transparent;
  transform: scale(1.08) translateY(-2px);
  box-shadow: 0 8px 20px rgba(0, 122, 255, 0.30);
}
.tag-cloud-count { font-size: 12px; color: var(--text-tertiary); margin-left: 4px; }
.tag-cloud-item:hover .tag-cloud-count { color: rgba(255,255,255,0.75); }

/* ====== Knowledge Graph Canvas Container ====== */
.kg-section {
  margin-top: 56px;
  padding: 32px;
  position: relative;
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--glass-shadow-outer);
  overflow: hidden;
}
.kg-section::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 50%;
  border-radius: inherit;
  background: linear-gradient(
    135deg,
    rgba(255, 255, 255, var(--refraction-light)) 0%,
    rgba(255, 255, 255, 0.04) 50%,
    transparent 100%
  );
  pointer-events: none;
  z-index: 1;
}
.section-title { font-size: 20px; font-weight: 700; letter-spacing: -0.4px; margin-bottom: 6px; position: relative; z-index: 2; }
.section-desc { font-size: 14px; color: var(--text-secondary); margin-bottom: 20px; position: relative; z-index: 2; }
.graph-wrapper {
  overflow: hidden;
  border-radius: var(--radius-md);
  background: var(--bg-secondary);
  border: 1px solid var(--glass-border);
  position: relative; z-index: 2;
}
#kg-canvas { display: block; width: 100%; height: 400px; }

.no-results { text-align: center; padding: 60px 20px; color: var(--text-secondary); }

/* ====== Footer ====== */
.page-footer {
  margin-top: 64px;
  padding: 24px 0;
  text-align: center;
  font-size: 13px;
  color: var(--text-tertiary);
}

/* ====== Breadcrumb Navigation ====== */
.breadcrumb { display: flex; align-items: center; gap: 8px; padding: 20px 0; font-size: 14px; }
.breadcrumb-link {
  display: inline-flex; align-items: center; gap: 5px;
  color: var(--accent);
  text-decoration: none;
  font-weight: 500;
  transition: all var(--spring-smooth);
  padding: 4px 10px;
  border-radius: 8px;
}
.breadcrumb-link:hover {
  background: var(--accent-light);
  text-decoration: none;
  transform: translateX(2px);
}
.breadcrumb-sep { color: var(--text-tertiary); }
.breadcrumb-current {
  color: var(--text-secondary);
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ====== Detail Page Header (Glass Card) ====== */
.detail-header { padding: 28px 30px; margin-bottom: 20px; }
.detail-title-row { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; position: relative; z-index: 2; }
.detail-title {
  font-size: clamp(22px, 4vw, 30px);
  font-weight: 700;
  letter-spacing: -0.5px;
  line-height: 1.25;
}
.pin-badge {
  display: inline-block;
  padding: 4px 12px;
  font-size: 12px;
  font-weight: 600;
  color: var(--color-quick);
  background: rgba(243,156,18,0.12);
  border: 1px solid rgba(243,156,18,0.25);
  border-radius: 999px;
  flex-shrink: 0;
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}
.detail-meta { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; position: relative; z-index: 2; }
.meta-chip {
  display: inline-block;
  padding: 4px 12px;
  font-size: 12px;
  font-weight: 500;
  border-radius: 10px;
  background: var(--glass-bg);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  color: var(--text-secondary);
  border: 1px solid var(--glass-border);
  transition: all var(--spring-smooth);
}
.meta-chip:hover { border-color: var(--glass-border-hover); background: var(--glass-bg-hover); }
.type-chip.type-text     { color: var(--color-text);   border-color: rgba(0,122,255,0.25); }
.type-chip.type-asr      { color: var(--color-asr);    border-color: rgba(230,126,34,0.25); }
.type-chip.type-meeting  { color: var(--color-meeting); border-color: rgba(155,89,182,0.25); }
.type-chip.type-link     { color: var(--color-link);   border-color: rgba(52,152,219,0.25); }
.type-chip.type-quick    { color: var(--color-quick);  border-color: rgba(243,156,18,0.25); }
.type-chip.type-aichat,
.type-chip.type-ai_chat  { color: var(--color-aichat); border-color: rgba(26,188,156,0.25); }
.type-chip.type-image    { color: var(--color-image);  border-color: rgba(46,204,113,0.25); }
.type-chip.type-pdf      { color: var(--color-pdf);    border-color: rgba(231,76,60,0.25); }
.type-chip.type-audio    { color: var(--color-audio);  border-color: rgba(175,82,222,0.25); }
.type-chip.type-book     { color: var(--color-book);   border-color: rgba(52,73,94,0.25); }

.detail-tags { display: flex; flex-wrap: wrap; gap: 6px; position: relative; z-index: 2; }
.tag-chip {
  display: inline-block;
  padding: 4px 13px;
  font-size: 12px;
  font-weight: 500;
  background: var(--glass-bg);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  color: var(--accent);
  border: 1px solid var(--glass-border);
  border-radius: 999px;
  transition: all var(--spring-smooth);
}
.tag-chip:hover { background: var(--accent); color: var(--text-on-accent); border-color: transparent; transform: scale(1.05); }

/* ====== Source Card (Glass) ====== */
.source-card { padding: 18px 24px; margin-bottom: 16px; }
.source-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; position: relative; z-index: 2; }
.source-badge {
  font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.4px;
  color: var(--text-secondary);
  background: var(--glass-bg);
  padding: 3px 10px;
  border-radius: 8px;
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border: 1px solid var(--glass-border);
}
.source-label { font-size: 12px; color: var(--text-tertiary); font-weight: 500; }
.source-link { font-size: 14px; color: var(--accent); word-break: break-all; text-decoration: none; position: relative; z-index: 2; }
.source-link:hover { text-decoration: underline; }

/* ====== Audio Player (Glass Skin) ====== */
.audio-player-card { padding: 18px 24px; margin-bottom: 16px; }
.audio-header { margin-bottom: 10px; position: relative; z-index: 2; }
.audio-label { font-size: 12px; color: var(--text-tertiary); font-weight: 500; }
.audio-element {
  width: 100%; height: 42px;
  border-radius: var(--radius-sm);
  outline: none;
  position: relative; z-index: 2;
}
.audio-element::-webkit-media-controls-panel {
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border-radius: var(--radius-sm);
}

/* ====== Detail Content Area ====== */
.detail-content { padding: 28px 30px; margin-bottom: 24px; }
.markdown-body {
  font-size: 15px;
  line-height: 1.8;
  color: var(--text-primary);
  word-wrap: break-word;
  position: relative; z-index: 2;
}
.markdown-body h1,.markdown-body h2,.markdown-body h3,.markdown-body h4 {
  font-weight: 700; letter-spacing: -0.3px;
  margin: 1.4em 0 0.5em; line-height: 1.3;
}
.markdown-body h1 { font-size: 1.6em; }
.markdown-body h2 { font-size: 1.35em; }
.markdown-body h3 { font-size: 1.15em; }
.markdown-body p { margin: 0.8em 0; }
.markdown-body ul,.markdown-body ol { padding-left: 1.6em; margin: 0.8em 0; }
.markdown-body li { margin: 0.3em 0; }
.markdown-body blockquote {
  border-left: 3px solid var(--accent);
  padding: 10px 18px;
  margin: 1em 0;
  color: var(--text-secondary);
  background: var(--glass-bg);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  border-right: 1px solid var(--glass-border);
  border-top: 1px solid var(--glass-border);
  border-bottom: 1px solid var(--glass-border);
}
.markdown-body code {
  font-family: var(--mono-stack);
  font-size: 0.88em;
  background: var(--glass-bg);
  padding: 2px 7px;
  border-radius: 6px;
  border: 1px solid var(--glass-border);
}
.markdown-body pre {
  background: var(--glass-bg-solid);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  padding: 18px;
  overflow-x: auto;
  margin: 1em 0;
  box-shadow: var(--glass-shadow-outer);
}
.markdown-body pre code { background: none; padding: 0; border: none; }
.markdown-body strong { font-weight: 650; }
.markdown-body em { font-style: italic; }
.markdown-body a { color: var(--accent); text-decoration: none; }
.markdown-body a:hover { text-decoration: underline; }
.markdown-body hr {
  border: none;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--glass-border), transparent);
  margin: 2em 0;
}
.markdown-body table {
  width: 100%;
  border-collapse: collapse;
  margin: 1em 0;
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.markdown-body th,.markdown-body td {
  border: 1px solid var(--glass-border);
  padding: 10px 14px;
  text-align: left;
  font-size: 14px;
}
.markdown-body th {
  font-weight: 600;
  background: var(--glass-bg);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
}
.markdown-body img {
  max-width: 100%;
  border-radius: var(--radius-md);
  margin: 1em 0;
  box-shadow: var(--glass-shadow-outer);
}

/* ====== Sprout Report Section ====== */
.sprout-report-section { margin-top: 24px; }
.sprout-header {
  padding: 24px 28px;
  margin-bottom: 20px;
  background: var(--sprout-bg);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  position: relative; overflow: hidden;
}
.sprout-main-title { font-size: 20px; font-weight: 700; letter-spacing: -0.4px; margin-bottom: 12px; position: relative; z-index: 2; }
.sprout-meta-row { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 10px; position: relative; z-index: 2; }
.sprout-meta-item {
  font-size: 12px; color: var(--text-secondary);
  background: var(--glass-bg);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  padding: 4px 12px;
  border-radius: 10px;
  border: 1px solid var(--glass-border);
}
.sprout-summary { font-size: 14px; color: var(--text-secondary); line-height: 1.6; font-style: italic; position: relative; z-index: 2; }
.sprout-articles { display: flex; flex-direction: column; gap: 20px; }
.sprout-article {
  position: relative;
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  padding: 26px 28px;
  box-shadow: var(--glass-shadow-outer);
  overflow: hidden;
  transition: all var(--spring-smooth);
}
.sprout-article::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 50%;
  border-radius: inherit;
  background: linear-gradient(
    135deg,
    rgba(255, 255, 255, var(--refraction-light)) 0%,
    rgba(255, 255, 255, 0.04) 50%,
    transparent 100%
  );
  pointer-events: none;
  z-index: 1;
}
.sprout-article:hover { border-color: var(--glass-border-hover); box-shadow: var(--glass-shadow-hover); }
.sprout-article-title { font-size: 17px; font-weight: 700; letter-spacing: -0.3px; margin-bottom: 16px; color: var(--text-primary); position: relative; z-index: 2; }
.sprout-seed { margin-bottom: 16px; position: relative; z-index: 2; }
.seed-label {
  display: inline-block;
  font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px;
  color: var(--seed-text); margin-bottom: 6px;
}
.seed-quote {
  font-style: italic; color: var(--seed-text); font-size: 14px; line-height: 1.65;
  border-left: 3px solid var(--text-tertiary);
  padding: 10px 16px;
  background: var(--glass-bg);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  border-right: 1px solid var(--glass-border);
  border-top: 1px solid var(--glass-border);
  border-bottom: 1px solid var(--glass-border);
  margin: 0;
}
.sprout-body { font-size: 15px; line-height: 1.8; color: var(--text-primary); margin-bottom: 16px; position: relative; z-index: 2; }
.sprout-body p { margin: 0.7em 0; }
.sprout-body ul,.sprout-body ol { padding-left: 1.6em; margin: 0.7em 0; }
.sprout-body li { margin: 0.25em 0; }
.sprout-body strong { font-weight: 650; }

/* 震惊瞬间 Card */
.aha-moment { margin-bottom: 12px; position: relative; z-index: 2; }
.aha-label {
  display: inline-block;
  font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px;
  color: #D97706; margin-bottom: 6px;
}
.aha-quote {
  border-left: 3px solid var(--aha-border-left);
  padding: 14px 20px;
  background: var(--aha-bg);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border-radius: 0 var(--radius-md) var(--radius-md) 0;
  border-right: 1px solid var(--glass-border);
  border-top: 1px solid var(--glass-border);
  border-bottom: 1px solid var(--glass-border);
  font-size: 15px; font-weight: 500; line-height: 1.6;
  color: var(--text-primary); margin: 0;
}
.sprout-importance { font-size: 12px; color: var(--text-tertiary); position: relative; z-index: 2; }
.stars { letter-spacing: 2px; color: var(--color-quick); }

/* Action Items (Glass Card) */
.action-items-section {
  margin-top: 20px;
  position: relative;
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  padding: 24px 28px;
  box-shadow: var(--glass-shadow-outer);
  overflow: hidden;
}
.action-items-section::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 50%;
  border-radius: inherit;
  background: linear-gradient(
    135deg,
    rgba(255, 255, 255, var(--refraction-light)) 0%,
    rgba(255, 255, 255, 0.04) 50%,
    transparent 100%
  );
  pointer-events: none;
  z-index: 1;
}
.action-title { font-size: 16px; font-weight: 600; margin-bottom: 14px; position: relative; z-index: 2; }
.action-list { list-style: none; padding: 0; }
.action-list li { margin: 8px 0; }
.action-list label {
  display: flex; align-items: flex-start; gap: 10px;
  cursor: pointer; font-size: 14px; line-height: 1.5;
  position: relative; z-index: 2;
}
.action-list input[type="checkbox"] {
  margin-top: 3px;
  accent-color: var(--accent);
  width: 17px; height: 17px;
  flex-shrink: 0;
  border-radius: 4px;
}

/* Related Concepts (Glass Chips) */
.concepts-section { margin-top: 16px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.concepts-label { font-size: 12px; color: var(--text-tertiary); font-weight: 500; }
.concepts-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.concept-chip {
  display: inline-block;
  padding: 4px 13px;
  font-size: 12px;
  font-weight: 500;
  background: rgba(168, 85, 247, 0.10);
  color: #A855F7;
  border: 1px solid rgba(168, 85, 247, 0.20);
  border-radius: 999px;
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  transition: all var(--spring-smooth);
}
.concept-chip:hover { background: rgba(168, 85, 247, 0.20); transform: scale(1.05); }

/* ====== Scroll Reveal Animation (Fade-Up + Subtle Scale) ====== */
.reveal {
  opacity: 0;
  transform: translateY(24px) scale(0.98);
  transition:
    opacity 0.65s cubic-bezier(0.34, 1.56, 0.64, 1),
    transform 0.65s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.reveal.visible {
  opacity: 1;
  transform: translateY(0) scale(1);
}

/* ====== Responsive ====== */
@media (max-width: 640px) {
  .app-container { padding: 0 16px 40px; }
  .top-nav { flex-direction: column; gap: 12px; align-items: flex-start; }
  .top-nav::after { left: -16px; right: -16px; }
  .hero-title { font-size: 28px; }
  .stats-row { grid-template-columns: repeat(2, 1fr); gap: 10px; }
  .stat-card { padding: 18px 14px; }
  .search-bar { padding: 12px 16px; }
  .note-card { padding: 16px 18px; }
  .detail-header,.detail-content,.sprout-article,.action-items-section { padding: 20px; }
  .sprout-header { padding: 20px; }
  .view-switcher { width: 100%; justify-content: stretch; }
  .view-btn { flex: 1; justify-content: center; }
  .kg-section { padding: 20px; }
  .sprout-meta-row { flex-direction: column; gap: 6px; }
  .detail-title-row { flex-direction: column; align-items: flex-start; gap: 8px; }
}

/* ====== Three-Layer Tag System ====== */
.tag-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  position: relative; z-index: 2;
}
.tag-pill {
  display: inline-flex;
  align-items: center;
  padding: 5px 14px;
  font-size: 12px;
  font-weight: 500;
  border-radius: 999px;
  letter-spacing: 0.2px;
  transition: all var(--spring-smooth);
  white-space: nowrap;
}
.tag-pill.system {
  background: var(--glass-bg-solid);
  color: var(--text-primary);
  border: none;
  border-left: 3px solid var(--tag-system-color, var(--accent));
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
}
.tag-pill.user {
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--glass-border);
  backdrop-filter: blur(6px) saturate(140%);
  -webkit-backdrop-filter: blur(6px) saturate(140%);
}
.tag-pill.user:hover {
  background: var(--glass-bg-hover);
  border-color: var(--glass-border-hover);
  color: var(--text-primary);
}
.tag-pill.ai {
  background: transparent;
  border: 1.5px dashed rgba(168,85,247,0.45);
  color: #A855F7;
  background-image: linear-gradient(135deg, #A855F7, #EC4899);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.tag-pill.ai:hover {
  background: rgba(168,85,247,0.08);
  -webkit-text-fill-color: #A855F7;
  border-style: solid;
}

/* System tag per-type left bar colors */
.tag-pill.system.type-text     { --tag-system-color: #4A90D9; }
.tag-pill.system.type-asr      { --tag-system-color: #E67E22; }
.tag-pill.system.type-meeting  { --tag-system-color: #9B59B6; }
.tag-pill.system.type-link     { --tag-system-color: #3498DB; }
.tag-pill.system.type-quick    { --tag-system-color: #F39C12; }
.tag-pill.system.type-aichat,
.tag-pill.system.type-ai_chat  { --tag-system-color: #1ABC9C; }
.tag-pill.system.type-image    { --tag-system-color: #2ECC71; }
.tag-pill.system.type-pdf      { --tag-system-color: #E74C3C; }
.tag-pill.system.type-audio    { --tag-system-color: #AF52DE; }
.tag-pill.system.type-book     { --tag-system-color: #34495E; }

/* ====== Tab Bar (Detail Page Navigation) ====== */
.tab-bar {
  display: flex;
  gap: 6px;
  margin-bottom: 20px;
  padding: 5px;
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  box-shadow: var(--glass-shadow-outer);
  overflow-x: auto;
  -ms-overflow-style: none;
  scrollbar-width: none;
}
.tab-bar::-webkit-scrollbar { display: none; }
.tab-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 8px 18px;
  border: none;
  border-radius: calc(var(--radius-sm) - 2px);
  background: transparent;
  font-family: var(--font-stack);
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--spring-smooth);
  white-space: nowrap;
  flex-shrink: 0;
  position: relative; z-index: 1;
}
.tab-pill:hover { color: var(--text-primary); background: rgba(255,255,255,0.06); }
.tab-pill.active {
  background: var(--accent);
  color: var(--text-on-accent);
  box-shadow: 0 2px 8px rgba(0,122,255,0.30);
  font-weight: 600;
}
.tab-content { display: none; }
.tab-content.active { display: block; }

/* ====== Waveform Audio Player (GetNotes Brain Style) ====== */
.waveform-player {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 22px;
  margin-bottom: 20px;
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  box-shadow: var(--glass-shadow-outer);
  position: relative; z-index: 2;
}
.waveform-player::before {
  content: '';
  position: absolute; inset: 0;
  border-radius: inherit;
  background: linear-gradient(135deg, rgba(255,255,255,var(--refraction-light)) 0%, transparent 60%);
  pointer-events: none;
}
.play-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 42px; height: 42px; flex-shrink: 0;
  border: none; border-radius: 50%;
  background: linear-gradient(135deg, var(--accent-blue), var(--accent-purple));
  color: #fff; cursor: pointer;
  box-shadow: 0 4px 14px rgba(0,122,255,0.35);
  transition: all var(--spring-smooth);
  position: relative; z-index: 2;
}
.play-btn:hover { transform: scale(1.08); box-shadow: 0 6px 20px rgba(0,122,255,0.45); }
.play-btn:active { transform: scale(0.94); }
.play-btn svg { width: 18px; height: 18px; fill: currentColor; }
.waveform-container {
  flex: 1;
  height: 48px;
  cursor: pointer;
  position: relative;
  display: flex;
  align-items: center;
  gap: 2px;
  user-select: none;
  z-index: 2;
}
.w-bars {
  display: flex;
  align-items: center;
  gap: 2px;
  width: 100%; height: 100%;
}
.w-bar {
  flex: 1;
  min-width: 3px;
  max-width: 6px;
  border-radius: 2px;
  background: var(--glass-border);
  transition: height 0.15s ease, background 0.2s ease;
  position: relative;
}
.w-bar.active { background: linear-gradient(180deg, var(--accent-blue), var(--accent-purple)); }
.w-bar.played { background: linear-gradient(180deg, var(--accent-blue), var(--accent-cyan)); opacity: 0.7; }
.progress-line {
  position: absolute;
  top: 0; bottom: 0;
  width: 2px;
  background: var(--accent-blue);
  border-radius: 1px;
  pointer-events: none;
  z-index: 3;
  box-shadow: 0 0 6px rgba(0,122,255,0.40);
  transition: left 0.08s linear;
}
.duration {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-tertiary);
  min-width: 48px;
  text-align: right;
  font-variant-numeric: tabular-nums;
  position: relative; z-index: 2;
  font-family: var(--mono-stack);
}

/* ====== Chapter List (Timestamp Parsed Sections) ====== */
.chapter-list {
  margin-top: 16px;
  padding: 16px 20px;
  background: var(--glass-bg);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-sm);
  position: relative; z-index: 2;
}
.chapter-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 4px;
  cursor: pointer;
  border-bottom: 1px solid var(--glass-border);
  transition: background var(--ease-standard);
}
.chapter-item:last-child { border-bottom: none; }
.chapter-item:hover { background: var(--accent-light); border-radius: 6px; }
.chapter-time {
  font-size: 12px;
  font-weight: 600;
  font-family: var(--mono-stack);
  color: var(--accent);
  min-width: 68px;
  flex-shrink: 0;
}
.chapter-title {
  font-size: 14px;
  color: var(--text-primary);
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ====== Quote Card (Original Content Display) ====== */
.quote-card {
  padding: 20px 24px;
  margin-bottom: 16px;
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  box-shadow: var(--glass-shadow-outer);
  position: relative; overflow: hidden;
}
.quote-card::before {
  content: '';
  position: absolute; inset: 0;
  border-radius: inherit;
  background: linear-gradient(135deg, rgba(255,255,255,var(--refraction-light)) 0%, transparent 60%);
  pointer-events: none; z-index: 1;
}
.quote-card-label {
  font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px;
  color: var(--text-tertiary); margin-bottom: 10px; position: relative; z-index: 2;
}
.quote-card-body {
  font-size: 14px; line-height: 1.7; color: var(--text-secondary);
  position: relative; z-index: 2;
  max-height: 300px; overflow-y: auto;
  word-break: break-word;
}
.quote-card-empty {
  display: flex; align-items: center; justify-content: center;
  padding: 32px 20px; color: var(--text-tertiary); font-size: 14px;
}

/* ====== Print Styles ====== */

/* === Liquid Glass Optical Refraction Layer === */
/* When JS engine generates displacement map, this enhancement layer auto-activates */
.liquid-glass-active .glass-card,
.liquid-glass-active .stat-card,
.liquid-glass-active .note-card,
.liquid-glass-active .timeline-item,
.liquid-glass-active .tag-chip,
.liquid-glass-active .meta-card,
.liquid-glass-active .sprout-section,
.liquid-glass-active .source-card,
.liquid-glass-active .related-card {
  backdrop-filter:
    url(#lg-refraction),
    blur(0.5px),
    contrast(1.15),
    brightness(1.04),
    saturate(1.12);
  -webkit-backdrop-filter:
    url(#lg-refraction),
    blur(0.5px),
    contrast(1.15),
    brightness(1.04),
    saturate(1.12);
}

@media print {
  body {
    background: #fff !important;
    color: #000 !important;
    -webkit-print-color-adjust: exact;
    print-color-adjust: exact;
  }
  .bg-orbs,
  .top-nav,
  .search-section,
  .view-switcher,
  .btn-icon,
  .audio-player-card,
  .stats-row { display: none !important; }
  .glass-card {
    background: #fff !important;
    backdrop-filter: none !important;
    -webkit-backdrop-filter: none !important;
    border: 1px solid #ddd !important;
    box-shadow: none !important;
    break-inside: avoid;
  }
  .glass-card::before { display: none !important; }
  .app-container { max-width: 100% !important; padding: 0 !important; }
  .reveal { opacity: 1 !important; transform: none !important; transition: none !important; }
  a { color: #0066cc !important; text-decoration: underline !important; }
  .note-card {
    page-break-inside: avoid;
    border: 1px solid #ddd !important;
    box-shadow: none !important;
    background: #fff !important;
  }
  .note-card::before { display: none !important; }
  .sprout-report-section { page-break-inside: avoid; }
  .sprout-article {
    page-break-inside: avoid;
    background: #fff !important;
    border: 1px solid #ddd !important;
  }
  .sprout-article::before { display: none !important; }
  .action-items-section {
    page-break-inside: avoid;
    background: #fff !important;
    border: 1px solid #ddd !important;
  }
  .action-items-section::before { display: none !important; }
  .kg-section {
    page-break-inside: avoid;
    background: #fff !important;
    border: 1px solid #ddd !important;
  }
  .kg-section::before { display: none !important; }
  .stat-card {
    page-break-inside: avoid;
    background: #fff !important;
    border: 1px solid #ddd !important;
    box-shadow: none !important;
  }
  .stat-card::before { display: none !important; }
  .tag-cloud-item,
  .note-card-tag,
  .tag-chip,
  .meta-chip,
  .card-type-badge {
    border: 1px solid #ccc !important;
    background: #f5f5f5 !important;
    print-color-adjust: exact;
    -webkit-print-color-adjust: exact;
  }
  .timeline-item::before {
    background: #333 !important;
    border-color: #fff !important;
    box-shadow: none !important;
  }
  .timeline-container::before {
    background: #ccc !important;
    box-shadow: none !important;
  }
  .markdown-body blockquote,
  .markdown-body pre,
  .markdown-body code {
    background: #f5f5f5 !important;
    border-color: #ddd !important;
    print-color-adjust: exact;
    -webkit-print-color-adjust: exact;
  }
}
"""
}

// ---------------------------------------------------------------------------
// 内部：生成 app.js (共享逻辑) - 使用 StringBuilder 避免单引号解析冲突
// ---------------------------------------------------------------------------
private fun generateAppJs(): String {
    val sb = StringBuilder()
    sb.appendLine("(function () {")
    sb.appendLine("  'use strict';")
    sb.appendLine("")
    sb.appendLine("  function $(sel, ctx) { return (ctx || document).querySelector(sel); }")
    sb.appendLine("  function $$(sel, ctx) { return Array.from((ctx || document).querySelectorAll(sel)); }")
    sb.appendLine("")
    sb.appendLine("  function esc(str) { var d = document.createElement('div'); d.textContent = str; return d.innerHTML; }")
    sb.appendLine("")
    sb.appendLine("  function fmtDate(ts) {")
    sb.appendLine("    var d = new Date(ts);")
    sb.appendLine("    var pad = function (n) { return n < 10 ? '0' + n : '' + n; };")
    sb.appendLine("    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());")
    sb.appendLine("  }")
    sb.appendLine("")
    sb.appendLine("  (function initTheme() {")
    sb.appendLine("    var btn = $('#theme-toggle'); if (!btn) return;")
    sb.appendLine("    var sun = btn.querySelector('.icon-sun');")
    sb.appendLine("    var moon = btn.querySelector('.icon-moon');")
    sb.appendLine("    var saved = localStorage.getItem('opedrgent-theme');")
    sb.appendLine("    if (saved === 'light') { document.body.classList.remove('dark-mode'); document.body.classList.add('light-mode'); if (sun) sun.style.display = 'none'; if (moon) moon.style.display = 'inline'; }")
    sb.appendLine("    else if (saved === 'dark') { document.body.classList.remove('light-mode'); document.body.classList.add('dark-mode'); if (sun) sun.style.display = 'inline'; if (moon) moon.style.display = 'none'; }")
    sb.appendLine("    else { var isDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches; if (isDark) { if (sun) sun.style.display = 'inline'; if (moon) moon.style.display = 'none'; } else { if (sun) sun.style.display = 'none'; if (moon) moon.style.display = 'inline'; } }")
    sb.appendLine("    btn.addEventListener('click', function () {")
    sb.appendLine("      var isLight = document.body.classList.contains('light-mode') || (!document.body.classList.contains('dark-mode') && window.matchMedia && !window.matchMedia('(prefers-color-scheme: dark)').matches);")
    sb.appendLine("      if (isLight || (!document.body.classList.contains('dark-mode') && !document.body.classList.contains('light-mode'))) {")
    sb.appendLine("        document.body.classList.remove('light-mode'); document.body.classList.add('dark-mode'); localStorage.setItem('opedrgent-theme', 'dark'); if (sun) sun.style.display = 'inline'; if (moon) moon.style.display = 'none';")
    sb.appendLine("      } else { document.body.classList.remove('dark-mode'); document.body.classList.add('light-mode'); localStorage.setItem('opedrgent-theme', 'light'); if (sun) sun.style.display = 'none'; if (moon) moon.style.display = 'inline'; }")
    sb.appendLine("    });")
    sb.appendLine("  })();")
    sb.appendLine("")
    sb.appendLine("  (function initReveal() {")
    sb.appendLine("    if (!('IntersectionObserver' in window)) { $$('.reveal').forEach(function (el) { el.classList.add('visible'); }); return; }")
    sb.appendLine("    var observer = new IntersectionObserver(function (entries) { entries.forEach(function (entry) { if (entry.isIntersecting) { entry.target.classList.add('visible'); observer.unobserve(entry.target); } }); }, { threshold: 0.1 });")
    sb.appendLine("    $$('.reveal').forEach(function (el) { observer.observe(el); });")
    sb.appendLine("  })();")
    sb.appendLine("")
    sb.appendLine("  var notesDataEl = $('#notes-data'); if (!notesDataEl) return;")
    sb.appendLine("  try { var allNotes = JSON.parse(notesDataEl.textContent || '[]'); } catch (e) { allNotes = []; }")
    sb.appendLine("")
    sb.appendLine("  var searchInput = $('#global-search'); var searchCount = $('#search-count'); var noResults = $('#no-results'); var notesList = $('#notes-list');")
    sb.appendLine("")
    sb.appendLine("  function renderNotes(notes) {")
    sb.appendLine("    if (!notesList) return;")
    sb.appendLine("    if (notes.length === 0) { notesList.innerHTML = ''; if (noResults) noResults.style.display = 'block'; if (searchCount) searchCount.textContent = '\u65e0\u7ed3\u679c'; return; }")
    sb.appendLine("    if (noResults) noResults.style.display = 'none';")
    sb.appendLine("    var html = notes.map(function (n) {")
    sb.appendLine("      var title = esc(n.title || '\u672a\u547d\u540d'); var summary = esc(n.summary || '');")
    sb.appendLine("      var typeClass = (n.type || 'TEXT').toLowerCase(); var typeDisp = esc(n.typeDisplay || '\u6587\u672c');")
    sb.appendLine("      var dateStr = fmtDate(n.updatedAt || n.createdAt); var href = 'notes/' + n.uuid + '.html';")
    sb.appendLine("      var pinHtml = n.isPinned ? '<span class=\"pin-indicator\">PIN</span>' : '';")
    sb.appendLine("      var sproutHtml = n.hasSprout ? '<span class=\"sprout-indicator\">SPROUT</span>' : '';")
    sb.appendLine("      var tagsHtml = ''; if (n.tags && n.tags.length > 0) { tagsHtml = n.tags.slice(0, 4).map(function (t) { return '<span class=\"note-card-tag\">' + esc(t) + '</span>'; }).join(''); }")
    sb.appendLine("      return '<a class=\"note-card\" href=\"' + href + '\"><div class=\"note-card-header\"><span class=\"note-card-title\">' + title + '</span><div class=\"note-card-badges\"><span class=\"card-type-badge type-' + typeClass + '\">' + typeDisp + '</span>' + pinHtml + sproutHtml + '</div></div>' + (summary ? '<div class=\"note-card-summary\">' + summary + '</div>' : '') + '<div class=\"note-card-footer\">' + tagsHtml + '<span class=\"note-card-time\">' + dateStr + '</span></div></a>';")
    sb.appendLine("    }).join('');")
    sb.appendLine("    notesList.innerHTML = html; if (searchCount) searchCount.textContent = notes.length + ' / ' + allNotes.length;")
    sb.appendLine("  }")
    sb.appendLine("")
    sb.appendLine("  function doSearch(query) { var q = query.trim().toLowerCase(); if (!q) { renderNotes(allNotes); return; } var filtered = allNotes.filter(function (n) { var st = (n.title||'')+' '+(n.summary||'')+' '+(n.tags?n.tags.join(' '):'')+' '+(n.typeDisplay||''); return st.toLowerCase().indexOf(q)!==-1; }); renderNotes(filtered); }")
    sb.appendLine("  if (searchInput) searchInput.addEventListener('input', function () { doSearch(this.value); });")
    sb.appendLine("")
    sb.appendLine("  var viewBtns = $$('.view-btn'); var viewPanels = $$('.view-panel');")
    sb.appendLine("  viewBtns.forEach(function (btn) { btn.addEventListener('click', function () {")
    sb.appendLine("    var tv = this.getAttribute('data-view');")
    sb.appendLine("    viewBtns.forEach(function(b){ b.classList.toggle('active',b===btn); b.setAttribute('aria-pressed',b===btn?'true':'false'); });")
    sb.appendLine("    viewPanels.forEach(function(p){ p.classList.toggle('active',p.id==='view-'+tv); });")
    sb.appendLine("    if(tv==='list') renderNotes(allNotes); if(tv==='timeline') renderTimeline(allNotes); if(tv==='tags') renderTagCloud(allNotes);")
    sb.appendLine("  }); });")
    sb.appendLine("")
    sb.appendLine("  function renderTimeline(notes) {")
    sb.appendLine("    var c = $('#timeline-container'); if (!c) return;")
    sb.appendLine("    var g = {};")
    sb.appendLine("    notes.forEach(function (n) {")
    sb.appendLine("      var d = new Date(n.createdAt || n.updatedT);")
    sb.appendLine("      var k = d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2);")
    sb.appendLine("      if (!g[k]) g[k] = [];")
    sb.appendLine("      g[k].push(n);")
    sb.appendLine("    });")
    sb.appendLine("    var ms = Object.keys(g).sort().reverse();")
    sb.appendLine("    var h = ms.map(function (m) {")
    sb.appendLine("      var its = g[m].map(function (n) {")
    sb.appendLine("        var t = esc(n.title || '${"\u672a\u547d\u540d"}');")
    sb.appendLine("        var tc = (n.type || 'TEXT').toLowerCase();")
    sb.appendLine("        var td = esc(n.typeDisplay || '${"\u6587\u672c"}');")
    sb.appendLine("        var ds = fmtDate(n.updatedAt || n.createdAt);")
    sb.appendLine("        var hf = 'notes/' + n.uuid + '.html';")
    sb.appendLine("        return '<div class=\"timeline-item\"><a class=\"note-card\" href=\"' + hf + '\" style=\"margin-bottom:0;\"><div class=\"note-card-header\"><span class=\"note-card-title\">' + t + '</span><span class=\"card-type-badge type-' + tc + '\">' + td + '</span></div><div class=\"note-card-time\" style=\"margin-left:0;\">' + ds + '</div></a></div>';")
    sb.appendLine("      }).join('');")
    sb.appendLine("      return '<div class=\"timeline-month-group\"><div class=\"timeline-month-title\">' + m + '</div>' + its + '</div>';")
    sb.appendLine("    }).join('');")
    sb.appendLine("    c.innerHTML = h;")
    sb.appendLine("  }")
    sb.appendLine("")
    sb.appendLine("  function renderTagCloud(notes) {")
    sb.appendLine("    var c = $('#tag-cloud'); if (!c) return;")
    sb.appendLine("    var tm = {};")
    sb.appendLine("    notes.forEach(function (n) {")
    sb.appendLine("      if (n.tags) n.tags.forEach(function (t) { tm[t] = (tm[t] || 0) + 1; });")
    sb.appendLine("    });")
    sb.appendLine("    var es = Object.keys(tm).sort(function (a, b) { return tm[b] - tm[a]; });")
    sb.appendLine("    var mx = es.length > 0 ? tm[es[0]] : 1;")
    sb.appendLine("    var h = es.map(function (t) {")
    sb.appendLine("      var cnt = tm[t]; var r = cnt / mx;")
    sb.appendLine("      var sz = 12 + Math.round(r * 14); var op = 0.6 + r * 0.4;")
    sb.appendLine("      return '<a class=\"tag-cloud-item\" href=\"#\" data-search=\"' + esc(t) + '\" style=\"font-size:' + sz + 'px;opacity:' + op.toFixed(2) + ';\">' + esc(t) + ' <span class=\"tag-cloud-count\">' + cnt + '</span></a>';")
    sb.appendLine("    }).join('');")
    sb.appendLine("    c.innerHTML = h;")
    sb.appendLine("    c.querySelectorAll('.tag-cloud-item').forEach(function (el) {")
    sb.appendLine("      el.addEventListener('click', function (e) {")
    sb.appendLine("        e.preventDefault(); var q = this.getAttribute('data-search');")
    sb.appendLine("        if (searchInput) searchInput.value = q; doSearch(q);")
    sb.appendLine("        viewBtns.forEach(function (b) {")
    sb.appendLine("          var ia = b.getAttribute('data-view') === 'list';")
    sb.appendLine("          b.classList.toggle('active', ia);")
    sb.appendLine("          b.setAttribute('aria-pressed', ia ? 'true' : 'false');")
    sb.appendLine("        });")
    sb.appendLine("        viewPanels.forEach(function (p) { p.classList.toggle('active', p.id === 'view-list'); });")
    sb.appendLine("      });")
    sb.appendLine("    });")
    sb.appendLine("  }")
    sb.appendLine("")
    sb.appendLine("  (function drawGraph(){")
    sb.appendLine("    var cv = $('#kg-canvas'); if (!cv) return;")
    sb.appendLine("    var de = $('#graph-data'); if (!de) return;")
    sb.appendLine("    var gd; try { gd = JSON.parse(de.textContent || '{\"nodes\":[],\"edges\":[]}'); } catch(e) { return; }")
    sb.appendLine("    var nds = gd.nodes || []; var eds = gd.edges || [];")
    sb.appendLine("    if (nds.length < 2) return;")
    sb.appendLine("    var ctx = cv.getContext('2d');")
    sb.appendLine("    var dpr = window.devicePixelRatio || 1;")
    sb.appendLine("    var rt = cv.getBoundingClientRect();")
    sb.appendLine("    cv.width = rt.width * dpr; cv.height = rt.height * dpr;")
    sb.appendLine("    ctx.scale(dpr, dpr);")
    sb.appendLine("    var W = rt.width, H = rt.height, cx = W / 2, cy = H / 2, nps = [], rad = Math.min(W, H) * 0.35;")
    sb.appendLine("    nds.forEach(function (nd, i) {")
    sb.appendLine("      var ang = (i / nds.length) * 2 * Math.PI - Math.PI / 2;")
    sb.appendLine("      var ro = (nd.size || 1) * 3;")
    sb.appendLine("      nps.push({ x: cx + Math.cos(ang) * (rad + ro), y: cy + Math.sin(ang) * (rad + ro), node: nd });")
    sb.appendLine("    });")
    sb.appendLine("    var clrs = ['#007AFF','#AF52DE','#34C759','#FF9F0A','#FF3B30','#5856D6','#FF2D55','#AC8E68'];")
    sb.appendLine("    function gNC(i) { return clrs[i % clrs.length]; }")
    sb.appendLine("    ctx.strokeStyle = 'rgba(0,0,0,0.07)'; ctx.lineWidth = 1;")
    sb.appendLine("    eds.forEach(function (ed) { var s = ed[0], t = ed[1];")
    sb.appendLine("      if (s < nps.length && t < nps.length) { ctx.beginPath(); ctx.moveTo(nps[s].x, nps[s].y); ctx.lineTo(nps[t].x, nps[t].y); ctx.stroke(); } })")
    sb.appendLine("    nps.forEach(function (np, i) {")
    sb.appendLine("      var nd = np.node; var sz = 8 + Math.min(nd.size || 1, 15) * 1.2;")
    sb.appendLine("      var clr = gNC(i);")
    sb.appendLine("      var glw = ctx.createRadialGradient(np.x, np.y, 0, np.x, np.y, sz * 2.5);")
    sb.appendLine("      glw.addColorStop(0, clr + '30'); glw.addColorStop(1, clr + '00');")
    sb.appendLine("      ctx.fillStyle = glw; ctx.beginPath(); ctx.arc(np.x, np.y, sz * 2.5, 0, Math.PI * 2); ctx.fill();")
    sb.appendLine("      ctx.fillStyle = clr; ctx.beginPath(); ctx.arc(np.x, np.y, sz, 0, Math.PI * 2); ctx.fill();")
    sb.appendLine("      ctx.fillStyle = getComputedStyle(document.body).getPropertyValue('--text-primary').trim() || '#1d1d1f';")
    sb.appendLine("      ctx.font = '12px -apple-system, BlinkMacSystemFont, \"SF Pro Text\", \"PingFang SC\", sans-serif';")
    sb.appendLine("      ctx.textAlign = 'center'; ctx.textBaseline = 'middle';")
    sb.appendLine("      var lb = (nd.label || '').length > 8 ? (nd.label || '').slice(0, 7) + '..' : (nd.label || '');")
    sb.appendLine("      ctx.fillText(lb, np.x, np.y + sz + 14);")
    sb.appendLine("    });")
    sb.appendLine("  })();")
    sb.appendLine("")
    sb.appendLine("  renderNotes(allNotes);")
    sb.appendLine("")
    sb.appendLine("  // --- Liquid Glass Refraction Engine ---")
    sb.appendLine("  (function initLiquidGlass() {")
    sb.appendLine("    function genMap(w, h) {")
    sb.appendLine("      var c = document.createElement('canvas');")
    sb.appendLine("      c.width = w; c.height = h;")
    sb.appendLine("      var x = c.getContext('2d'), d = x.createImageData(w, h), data = d.data;")
    sb.appendLine("      for (var y = 0; y < h; y++) {")
    sb.appendLine("        for (var px = 0; px < w; px++) {")
    sb.appendLine("          var u = px / w, v = y / h, nx = (u - 0.5) * 2, nv = (v - 0.5) * 2;")
    sb.appendLine("          var rw = 0.85, rh = 0.7, rr = 0.15;")
    sb.appendLine("          var qx = Math.abs(nx) - rw + rr, qv = Math.abs(nv) - rh + rr;")
    sb.appendLine("          var sdf = Math.min(Math.max(qx, qv), 0) + Math.hypot(Math.max(qx, 0), Math.max(qv, 0)) - rr;")
    sb.appendLine("          var ef = Math.max(0, 1 - sdf / 0.18);")
    sb.appendLine("          var se = ef * ef * (3 - 2 * ef);")
    sb.appendLine("          var ang = Math.atan2(nv, nx);")
    sb.appendLine("          var ps = se * 0.12;")
    sb.appendLine("          var dx = Math.cos(ang) * ps, dy = Math.sin(ang) * ps;")
    sb.appendLine("          var ns = (Math.sin(u * 40) * Math.cos(v * 30) + Math.sin(u * 15 + v * 20)) * 0.008;")
    sb.appendLine("          var i = (y * w + px) * 4;")
    sb.appendLine("          data[i] = Math.round((dx + ns + 0.5) * 255);")
    sb.appendLine("          data[i + 1] = Math.round((dy + ns * 0.7 + 0.5) * 255);")
    sb.appendLine("          data[i + 2] = 0; data[i + 3] = 255;")
    sb.appendLine("        }")
    sb.appendLine("      }")
    sb.appendLine("      x.putImageData(d, 0, 0); return c.toDataURL('image/png');")
    sb.appendLine("    }")
    sb.appendLine("    function applyLG() {")
    sb.appendLine("      var fi = document.getElementById('lg-displacement-map');")
    sb.appendLine("      if (!fi) return;")
    sb.appendLine("      fi.setAttributeNS('http://www.w3.org/1999/xlink', 'href', genMap(400, 300));")
    sb.appendLine("      document.documentElement.classList.add('liquid-glass-active');")
    sb.appendLine("    }")
    sb.appendLine("    if (document.readyState === 'loading') {")
    sb.appendLine("      document.addEventListener('DOMContentLoaded', applyLG);")
    sb.appendLine("    } else { applyLG(); }")
    sb.appendLine("  })();")
    sb.appendLine("")
    sb.appendLine("  // ========== Detail Page: Tab Switching ==========")
    sb.appendLine("  (function initDetailTabs() {")
    sb.appendLine("    var tabBar = $('#tabBar'); if (!tabBar) return;")
    sb.appendLine("    var pills = $$('.tab-pill', tabBar); var panels = $$('.tab-content');")
    sb.appendLine("    pills.forEach(function (pill) { pill.addEventListener('click', function () {")
    sb.appendLine("      var target = this.getAttribute('data-tab');")
    sb.appendLine("      pills.forEach(function (p) { p.classList.remove('active'); }); this.classList.add('active');")
    sb.appendLine("      panels.forEach(function (panel) { panel.classList.toggle('active', panel.id === 'tab-' + target); });")
    sb.appendLine("    }); });")
    sb.appendLine("  })();")
    sb.appendLine("")
    sb.appendLine("  // ========== Detail Page: Waveform Player ==========")
    sb.appendLine("  (function initWaveformPlayer() {")
    sb.appendLine("    var player = $('#waveformPlayer'); if (!player) return;")
    sb.appendLine("    var waveEl = $('#waveform'); var progressEl = $('#progress'); var playBtn = $('#playBtn');")
    sb.appendLine("    var container = $('#waveContainer'); var durationEl = $('#audioDuration');")
    sb.appendLine("    if (!waveEl || !playBtn || !container) return;")
    sb.appendLine("    var BAR_COUNT = 80; var isPlaying = false;")
    sb.appendLine("    var currentProgress = 0; var playInterval = null;")
    sb.appendLine("")
    sb.appendLine("    function generateBars() {")
    sb.appendLine("      waveEl.innerHTML = '';")
    sb.appendLine("      for (var i = 0; i < BAR_COUNT; i++) {")
    sb.appendLine("        var bar = document.createElement('div'); bar.className = 'w-bar';")
    sb.appendLine("        var h = 8 + Math.random() * 32;")
    sb.appendLine("        if (Math.random() < 0.08) h = 4 + Math.random() * 8;")
    sb.appendLine("        bar.style.height = h + 'px'; bar.dataset.index = i;")
    sb.appendLine("        waveEl.appendChild(bar);")
    sb.appendLine("      }")
    sb.appendLine("      updateBarStates();")
    sb.appendLine("    }")
    sb.appendLine("")
    sb.appendLine("    function updateBarStates() {")
    sb.appendLine("      var bars = $$('.w-bar', waveEl);")
    sb.appendLine("      var playedIdx = Math.floor(currentProgress * BAR_COUNT);")
    sb.appendLine("      bars.forEach(function (bar, idx) {")
    sb.appendLine("        bar.classList.remove('active', 'played');")
    sb.appendLine("        if (idx < playedIdx) bar.classList.add('played');")
    sb.appendLine("        else if (idx === playedIdx && isPlaying) bar.classList.add('active');")
    sb.appendLine("      });")
    sb.appendLine("      if (progressEl) progressEl.style.left = (currentProgress * 100) + '%';")
    sb.appendLine("    }")
    sb.appendLine("")
    sb.appendLine("    function updateDurationDisplay() {")
    sb.appendLine("      if (!durationEl) return;")
    sb.appendLine("      var totalSecStr = durationEl.textContent.trim();")
    sb.appendLine("      var parts = totalSecStr.split(':');")
    sb.appendLine("      var totalSec = (parseInt(parts[0]) || 0) * 60 + (parseInt(parts[1]) || 0);")
    sb.appendLine("      var curSec = Math.floor(currentProgress * totalSec);")
    sb.appendLine("      var m = Math.floor(curSec / 60); var s = curSec % 60;")
    sb.appendLine("      durationEl.textContent = ('0' + m).slice(-2) + ':' + ('0' + s).slice(-2) + ' / ' + totalSecStr;")
    sb.appendLine("    }")
    sb.appendLine("")
    sb.appendLine("    window.togglePlay = function () {")
    sb.appendLine("      isPlaying = !isPlaying;")
    sb.appendLine("      if (isPlaying) {")
    sb.appendLine("        playBtn.innerHTML = '<svg viewBox=\"0 0 24 24\"><rect x=\"6\" y=\"4\" width=\"4\" height=\"16\"/><rect x=\"14\" y=\"4\" width=\"4\" height=\"16\"/></svg>';")
    sb.appendLine("        if (playInterval) clearInterval(playInterval);")
    sb.appendLine("        playInterval = setInterval(function () {")
    sb.appendLine("          currentProgress += 0.005;")
    sb.appendLine("          if (currentProgress >= 1) { currentProgress = 1; window.togglePlay(); }")
    sb.appendLine("          updateBarStates(); updateDurationDisplay();")
    sb.appendLine("        }, 50);")
    sb.appendLine("      } else {")
    sb.appendLine("        playBtn.innerHTML = '<svg viewBox=\"0 0 24 24\"><polygon points=\"6,3 20,12 6,21\"/></svg>';")
    sb.appendLine("        if (playInterval) { clearInterval(playInterval); playInterval = null; }")
    sb.appendLine("      }")
    sb.appendLine("      updateBarStates();")
    sb.appendLine("    };")
    sb.appendLine("")
    sb.appendLine("    window.seekWave = function (evt) {")
    sb.appendLine("      if (!container) return;")
    sb.appendLine("      var rect = container.getBoundingClientRect();")
    sb.appendLine("      var x = evt.clientX - rect.left;")
    sb.appendLine("      currentProgress = Math.max(0, Math.min(1, x / rect.width));")
    sb.appendLine("      updateBarStates(); updateDurationDisplay();")
    sb.appendLine("    };")
    sb.appendLine("")
    sb.appendLine("    window.seekToChapter = function (timeRatio) {")
    sb.appendLine("      currentProgress = Math.max(0, Math.min(1, timeRatio));")
    sb.appendLine("      updateBarStates(); updateDurationDisplay();")
    sb.appendLine("    };")
    sb.appendLine("")
    sb.appendLine("    generateBars(); updateDurationDisplay();")
    sb.appendLine("  })();")
    sb.appendLine("")
    sb.appendLine("  // ========== Detail Page: Timestamp Chapter Parser ==========")
    sb.appendLine("  (function initChapterParser() {")
    sb.appendLine("    var mdBody = $('#markdownBody'); var chapterContainer = $('#chapterContainer');")
    sb.appendLine("    if (!mdBody || !chapterContainer) return;")
    sb.appendLine("    var textContent = mdBody.textContent || '';")
    sb.appendLine("    var tsRegex = /\\[(\\d{1,2}:\\d{2}(?::\\d{2})?)\\]\\s*(.+)/g;")
    sb.appendLine("    var matches = []; var match;")
    sb.appendLine("    while ((match = tsRegex.exec(textContent)) !== null) {")
    sb.appendLine("      matches.push({ raw: match[0], timeStr: match[1], title: match[2].trim(), index: match.index });")
    sb.appendLine("    }")
    sb.appendLine("    if (matches.length < 2) return;")
    sb.appendLine("")
    sb.appendLine("    function parseTimeToSeconds(tstr) {")
    sb.appendLine("      var parts = tstr.split(':').map(function (p) { return parseInt(p, 10); });")
    sb.appendLine("      if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2];")
    sb.appendLine("      if (parts.length === 2) return parts[0] * 60 + parts[1]; return 0;")
    sb.appendLine("    }")
    sb.appendLine("")
    sb.appendLine("    var maxSeconds = 0;")
    sb.appendLine("    matches.forEach(function (m) { var s = parseTimeToSeconds(m.timeStr); if (s > maxSeconds) maxSeconds = s; });")
    sb.appendLine("")
    sb.appendLine("    var html = '<div class=chapter-list><div class=quote-label style=\"position:relative;z-index:2;\">\\u7ae0\\u8282\\u5bfc\\u822a</div>';")
    sb.appendLine("    matches.forEach(function (m) {")
    sb.appendLine("      var secs = parseTimeToSeconds(m.timeStr);")
    sb.appendLine("      var ratio = maxSeconds > 0 ? secs / maxSeconds : 0;")
    sb.appendLine("      html += '<div class=chapter-item data-ratio=' + ratio.toFixed(4) + ' onclick=\"seekToChapter(' + ratio.toFixed(4) + ')\">';")
    sb.appendLine("      html += '<span class=chapter-time>' + esc(m.timeStr) + '</span>';")
    sb.appendLine("      html += '<span class=chapter-title>' + esc(m.title) + '</span></div>';")
    sb.appendLine("    });")
    sb.appendLine("    html += '</div>';")
    sb.appendLine("    chapterContainer.innerHTML = html;")
    sb.appendLine("  })();")
    sb.appendLine("})();")
    return sb.toString()
}

// ---------------------------------------------------------------------------
// 内部：简单 Markdown 转 HTML
// ---------------------------------------------------------------------------
private fun renderMarkdownSimple(md: String): String {
    if (md.isBlank()) return "<p style='color:var(--text-tertiary);'>暂无内容</p>"
    var result = escapeHtml(md)
    result = result.replace(Regex("""```(\w*)\n?([\s\S]*?)```""")) { match ->
        val lang = match.groupValues[1]
        val code = match.groupValues[2]
        "<pre><code${if (lang.isNotBlank()) " class=\"language-$lang\"" else ""}>$code</code></pre>"
    }
    result = result.replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }
    result = result.replace(Regex("^#### (.+)$", RegexOption.MULTILINE)) { "<h4>${it.groupValues[1]}</h4>" }
    result = result.replace(Regex("^### (.+)$", RegexOption.MULTILINE)) { "<h3>${it.groupValues[1]}</h3>" }
    result = result.replace(Regex("^## (.+)$", RegexOption.MULTILINE)) { "<h2>${it.groupValues[1]}</h2>" }
    result = result.replace(Regex("^# (.+)$", RegexOption.MULTILINE)) { "<h1>${it.groupValues[1]}</h1>" }
    result = result.replace(Regex("^---+$", RegexOption.MULTILINE), "<hr>")
    result = result.replace(Regex("^&gt; (.+)$", RegexOption.MULTILINE)) { "<blockquote>${it.groupValues[1]}</blockquote>" }
    result = result.replace(Regex("\\*\\*(.+?)\\*\\*")) { "<strong>${it.groupValues[1]}</strong>" }
    result = result.replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)")) { "<em>${it.groupValues[1]}</em>" }
    result = result.replace(Regex("\\[(.+?)\\]\\((.+?)\\)")) { "<a href=\"${escapeAttr(it.groupValues[2])}\" target=\"_blank\" rel=\"noopener\">${it.groupValues[1]}</a>" }
    result = result.replace(Regex("!\\[(.+?)\\]\\((.+?)\\)")) { "<img src=\"${escapeAttr(it.groupValues[2])}\" alt=\"${escapeAttr(it.groupValues[1])}\" loading=\"lazy\">" }
    result = result.replace(Regex("^[\\-*\\+] (.+)$", RegexOption.MULTILINE)) { "<li>${it.groupValues[1]}</li>" }
    result = result.replace(Regex("(<li>.*?</li>(?:\n<li>.*?</li>)*)", RegexOption.DOT_MATCHES_ALL)) { "<ul>\n${it.value}\n</ul>" }
    result = result.replace(Regex("^\\d+\\. (.+)$", RegexOption.MULTILINE)) { "<li>${it.groupValues[1]}</li>" }
    result = result.replace(Regex("(^\\|.+\\|\\n)+", RegexOption.MULTILINE)) { tableBlock ->
        val lines = tableBlock.value.trimEnd().split("\n")
        if (lines.size < 3) return@replace tableBlock.value
        val headerCells = lines[0].split("|").filter { it.isNotBlank() }.map { it.trim() }
        val sb = StringBuilder("<table><thead><tr>")
        headerCells.forEach { sb.append("<th>$it</th>") }
        sb.append("</tr></thead><tbody>")
        for (i in 2 until lines.size) {
            val cells = lines[i].split("|").filter { it.isNotBlank() }.map { it.trim() }
            sb.append("<tr>")
            cells.forEach { sb.append("<td>$it</td>") }
            sb.append("</tr>")
        }
        sb.append("</tbody></table>").toString()
    }
    result = result.replace(Regex("\n\n+"), "\n<p>\n</p>\n")
    result = result.replace(Regex("(?<!\n)\n(?!\n)"), "<br>\n")
    result = result.replace(Regex("<p>\\s*</p>"), "")
    return result
}

// ---------------------------------------------------------------------------
// 内部：HTML 转义
// ---------------------------------------------------------------------------
private fun escapeHtml(text: String): String {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
private fun escapeAttr(text: String): String {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;").replace("\n", " ").replace("\r", "")
}
