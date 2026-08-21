package top.hsyscn.opedrgent.utils

import java.io.File
import top.hsyscn.opedrgent.utils.DebugLog

object ContextFileLoader {

    private val contextCache = java.util.concurrent.ConcurrentHashMap<String, ContextFileEntry>()
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val MAX_CONTENT_CHARS = 20_000

    data class ContextFileEntry(
        val content: String,
        val loadedAt: Long,
        val filePath: String,
        val sourceType: String
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - loadedAt > CACHE_TTL_MS
    }

    fun loadContextFiles(cwd: String): ContextFileEntry? {
        contextCache[cwd]?.let { entry ->
            if (!entry.isExpired()) return entry
            contextCache.remove(cwd)
        }

        val cwdPath = File(cwd).absoluteFile

        val result = loadOpedrgentMd(cwdPath)
            ?: loadAgentsMd(cwdPath)
            ?: loadClaudeMd(cwdPath)
            ?: loadCursorRules(cwdPath)

        result?.let {
            contextCache[cwd] = it
            DebugLog.i("ContextFile: loaded ${it.sourceType} from ${it.filePath}")
        }

        return result
    }

    fun clearCache() {
        contextCache.clear()
        DebugLog.d("ContextFile: cache cleared")
    }

    private fun loadOpedrgentMd(cwd: File): ContextFileEntry? {
        var current = cwd
        var depth = 0
        val maxDepth = 10

        while (depth < maxDepth) {
            for (name in listOf(".opedrgent.md", "OPEDRGENT.md")) {
                val file = File(current, name)
                if (file.exists()) {
                    return parseContextFile(file, "opedrgent")
                }
            }
            if (File(current, ".git").exists()) break
            val parent = current.parentFile ?: break
            current = parent
            depth++
        }
        return null
    }

    private fun loadAgentsMd(cwd: File): ContextFileEntry? =
        loadSingleFile(cwd, "AGENTS.md", "agents")

    private fun loadClaudeMd(cwd: File): ContextFileEntry? =
        loadSingleFile(cwd, "CLAUDE.md", "claude")

    private fun loadCursorRules(cwd: File): ContextFileEntry? {
        val rulesFile = File(cwd, ".cursorrules")
        if (rulesFile.exists()) {
            return parseContextFile(rulesFile, "cursorrules")
        }

        val rulesDir = File(cwd, ".cursor/rules")
        if (rulesDir.isDirectory) {
            val mdcFiles = rulesDir.listFiles { f -> f.extension == "mdc" }?.sortedBy { it.name }
            if (!mdcFiles.isNullOrEmpty()) {
                val contents = mdcFiles.joinToString("\n\n") {
                    "## ${it.name}\n\n${it.readText(Charsets.UTF_8)}"
                }
                return ContextFileEntry(
                    content = "# 项目上下文 (.cursor/rules)\n\n$contents",
                    loadedAt = System.currentTimeMillis(),
                    filePath = rulesDir.absolutePath,
                    sourceType = "cursorrules"
                )
            }
        }
        return null
    }

    private fun loadSingleFile(cwd: File, fileName: String, type: String): ContextFileEntry? {
        val file = File(cwd, fileName)
        return if (file.exists()) parseContextFile(file, type) else null
    }

    private fun parseContextFile(file: File, type: String): ContextFileEntry {
        try {
            var content = file.readText(Charsets.UTF_8).trim()

            if (content.startsWith("---")) {
                val endFrontmatter = content.indexOf("---", 3)
                if (endFrontmatter != -1) {
                    content = content.substring(endFrontmatter + 3).trim()
                }
            }

            if (content.length > MAX_CONTENT_CHARS) {
                val headChars = (MAX_CONTENT_CHARS * 0.7).toInt()
                val tailChars = (MAX_CONTENT_CHARS * 0.3).toInt()
                content = content.take(headChars) +
                    "\n\n[...truncated ${file.name}: kept ${headChars}+${tailChars} of ${content.length} chars...]\n\n" +
                    content.takeLast(tailChars)
            }

            return ContextFileEntry(
                content = "# 项目上下文 (${file.name})\n\n$content",
                loadedAt = System.currentTimeMillis(),
                filePath = file.absolutePath,
                sourceType = type
            )
        } catch (e: Exception) {
            DebugLog.w("ContextFile: failed to read ${file.absolutePath}: ${e.message}")
            return ContextFileEntry(
                content = "",
                loadedAt = 0L,
                filePath = file.absolutePath,
                sourceType = type
            )
        }
    }
}
