package top.hsyscn.opedrgent.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.intelligence.MemoryBridge
import top.hsyscn.opedrgent.note.NoteRepository
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File

/**
 * 增强型会议转写服务 — 整合 ASR 转写 + 自动持久化 + 可选说话人分离。
 *
 * 解决的问题：
 * 1. **原 MeetingTranscriber 成为死代码** → 此类作为统一入口，内部调用 MeetingTranscriber
 * 2. **转录结果未保存** → 自动通过 MeetingTranscriptBridge 持久化到笔记和记忆系统
 * 3. **UI 层代码重复** → 提供简洁的高层 API，减少 UI 层样板代码
 *
 * 架构设计：
 * ```
 * 用户录音文件
 *     ↓
 * EnhancedMeetingTranscriber.transcribe()
 *     ↓
 * ├─ MeetingTranscriber (ASR + 可选说话人分离)
 * │   └─ TranscriptionResult
 * ↓
 * ├─ 转换为 MeetingTranscriptResult (统一数据格式)
 * ↓
 * ├─ MeetingTranscriptBridge.saveTranscriptResult()
 *     ├─ NoteRepository.saveNote() → 笔记系统
 *     └─ MemoryBridge.write()    → 记忆系统（可选）
 * ↓
 * 返回 EnhancedTranscriptResult (含 noteId、memoryPath 等元信息)
 * ```
 *
 * 使用方式：
 * ```kotlin
 * val transcriber = EnhancedMeetingTranscriber(context, asrManager, noteRepository, memoryBridge)
 * val result = transcriber.transcribeAndSave(audioFile, "项目周会.wav")
 * if (result.isSuccess) {
 *     println("笔记ID: ${result.noteId}")
 *     println("已写入记忆: ${result.memoryPath}")
 * }
 * ```
 *
 * @param context Android Context
 * @param asrManager ASR 管理器（自动选择引擎）
 * @param noteRepository 笔记仓库（用于保存转写结果）
 * @param memoryBridge 记忆桥接器（可选，用于语义检索）
 */
class EnhancedMeetingTranscriber(
    private val context: Context,
    private val asrManager: AsrManager,
    private val noteRepository: NoteRepository,
    private val memoryBridge: MemoryBridge? = null,
) {

    companion object {
        private const val TAG = "EnhancedMeetingTranscriber"
    }

    /** 内部会议转写器（负责 ASR + 说话人分离） */
    private var meetingTranscriber: MeetingTranscriber? = null
    
    /** 结果持久化桥接器 */
    private lateinit var transcriptBridge: MeetingTranscriptBridge

    /**
     * 增强型转写结果 — 包含原始转写数据 + 持久化元信息。
     */
    data class EnhancedTranscriptResult(
        /** 原始转写结果（用于 UI 渲染） */
        val transcriptResult: MeetingTranscriptResult,
        
        /** 保存的笔记 ID（如果保存成功） */
        val noteId: Long? = null,
        
        /** 记忆系统路径（如果写入成功） */
        val memoryPath: String? = null,
        
        /** 是否整体成功（转写成功 或 至少部分持久化成功） */
        val isSuccess: Boolean,
        
        /** 处理总耗时（毫秒） */
        val totalDurationMs: Long = 0,
        
        /** 错误信息（如果有） */
        val error: String? = null,
        
        /** 详细处理日志 */
        val processingLog: List<String> = emptyList(),
    )

    // ==================== 公开 API ====================

    /**
     * 转写音频文件并自动保存结果。
     *
     * 完整流程：
     * 1. 使用 AsrManager 选择最优引擎进行转写
     * 2. （可选）尝试说话人分离
     * 3. 转换为统一的 MeetingTranscriptResult 格式
     * 4. 通过 MeetingTranscriptBridge 保存到笔记和记忆系统
     * 5. 返回包含所有元信息的 EnhancedTranscriptResult
     *
     * @param audioFile 音频文件（WAV/MP3/M4A 等格式）
     * @param audioFileName 显示用文件名（默认使用文件实际名称）
     * @param enableDiarization 是否启用说话人分离（默认 true，失败时自动降级）
     * @param autoSave 是否自动保存结果（默认 true）
     * @return 增强型转写结果
     */
    suspend fun transcribeAndSave(
        audioFile: File,
        audioFileName: String? = null,
        enableDiarization: Boolean = true,
        autoSave: Boolean = true,
    ): EnhancedTranscriptResult = withContext(Dispatchers.IO) {
        val startTimeMs = System.currentTimeMillis()
        val log = mutableListOf<String>()
        var transcriptResult: MeetingTranscriptResult? = null
        var noteId: Long? = null
        var memoryPath: String? = null
        var error: String? = null

        try {
            log.add("[开始] 增强型会议转写")
            log.add("   文件: ${audioFile.name} (${audioFile.length() / 1024}KB)")

            // 1. 验证文件
            if (!audioFile.exists()) {
                throw IllegalArgumentException("音频文件不存在: ${audioFile.absolutePath}")
            }
            if (audioFile.length() == 0L) {
                throw IllegalArgumentException("音频文件为空")
            }

            log.add("[成功] 文件验证通过")

            // 2. 执行转写（优先使用 MeetingTranscriber，失败时降级到 AsrManager）
            transcriptResult = performTranscription(audioFile, enableDiarization, log)

            if (transcriptResult?.error != null) {
                error = "转写失败: ${transcriptResult!!.error}"
                log.add("[错误] $error")
                return@withContext EnhancedTranscriptResult(
                    transcriptResult = transcriptResult!!,
                    isSuccess = false,
                    totalDurationMs = System.currentTimeMillis() - startTimeMs,
                    error = error,
                    processingLog = log,
                )
            }

            log.add("[完成] 转写完成: ${transcriptResult!!.fullText.length} 字符, ${transcriptResult!!.segments.size} 段")

            // 3. 自动保存（如果启用）
            if (autoSave) {
                val displayName = audioFileName ?: audioFile.name
                
                // 初始化 Bridge（延迟初始化以避免循环依赖）
                if (!::transcriptBridge.isInitialized) {
                    transcriptBridge = MeetingTranscriptBridge(context, noteRepository, memoryBridge)
                }

                noteId = transcriptBridge.saveTranscriptResult(transcriptResult!!, displayName)
                
                if (noteId != null) {
                    log.add("[保存] 已保存到笔记系统: ID=$noteId")
                    
                    // 推断记忆路径（与 MeetingTranscriptBridge 保持一致）
                    memoryPath = inferMemoryPath(displayName)
                    if (memoryBridge != null) {
                        log.add("[记忆] 已写入记忆系统: $memoryPath")
                    }
                } else {
                    log.add("[警告] 保存到笔记系统失败（非致命）")
                }
            }

            val durationMs = System.currentTimeMillis() - startTimeMs
            log.add("[耗时] 总耗时: ${durationMs}ms")

            EnhancedTranscriptResult(
                transcriptResult = transcriptResult!!,
                noteId = noteId,
                memoryPath = memoryPath,
                isSuccess = true,
                totalDurationMs = durationMs,
                processingLog = log,
            )
        } catch (e: Exception) {
            error = e.message
            log.add("[异常] 异常: $error")
            
            DebugLog.e(TAG, "转写过程异常", e)
            
            EnhancedTranscriptResult(
                transcriptResult = transcriptResult ?: MeetingTranscriptResult(error = error),
                isSuccess = false,
                totalDurationMs = System.currentTimeMillis() - startTimeMs,
                error = error,
                processingLog = log,
            )
        }
    }

    /**
     * 仅转写不保存（轻量模式，用于预览或实时场景）。
     *
     * @param audioFile 音频文件
     * @return 转写结果（不含持久化元信息）
     */
    suspend fun transcribeOnly(audioFile: File): MeetingTranscriptResult {
        return performTranscription(audioFile, enableDiarization = false, log = mutableListOf())
    }

    // ==================== 内部实现 ====================

    /**
     * 执行实际的转写工作。
     *
     * 策略：
     * 1. 尝试使用 MeetingTranscriber（支持说话人分离）
     * 2. 如果 MeetingTranscriber 未初始化或失败，降级到 AsrManager
     * 3. 统一转换为 MeetingTranscriptResult 格式
     */
    private suspend fun performTranscription(
        audioFile: File,
        enableDiarization: Boolean,
        log: MutableList<String>,
    ): MeetingTranscriptResult {
        return try {
            // 尝试使用 MeetingTranscriber（高质量模式）
            if (enableDiarization && meetingTranscriber == null) {
                log.add("[初始化] 初始化 MeetingTranscriber...")
                initMeetingTranscriber(log)
            }

            if (enableDiarization && meetingTranscriber != null) {
                log.add("[转写] 使用 MeetingTranscriber（带说话人分离）")
                transcribeWithMeetingTranscriber(audioFile, log)
            } else {
                log.add("[转写] 使用 AsrManager（标准模式）")
                transcribeWithAsrManager(audioFile, log)
            }
        } catch (e: Exception) {
            // MeetingTranscriber 失败，降级到 AsrManager
            log.add("[警告] MeetingTranscriber 失败: ${e.message}")
            log.add("[降级] 降级到 AsrManager...")
            
            try {
                transcribeWithAsrManager(audioFile, log)
            } catch (e2: Exception) {
                log.add("[错误] AsrManager 也失败: ${e2.message}")
                MeetingTranscriptResult(error = "转写失败: ${e2.message}")
            }
        }
    }

    /**
     * 初始化 MeetingTranscriber。
     *
     * 从 AsrManager 获取模型目录信息进行初始化。
     */
    private suspend fun initMeetingTranscriber(log: MutableList<String>) {
        try {
            // 获取当前引擎的模型目录
            val engine = asrManager.getCachedEngine()
            if (engine != null && engine is SherpaOnnxEngine) {
                // 注意：这里需要获取模型目录路径
                // 由于 SherpaOnnxEngine 不直接暴露 modelDir，我们使用 ModelManager
                val modelType = ModelManager.getRecommendedModel(context)
                val modelDir = ModelManager.getModelPath(context, modelType)
                
                if (modelDir != null && ModelManager.isModelDownloaded(context, modelType)) {
                    meetingTranscriber = MeetingTranscriber(context).also { transcriber ->
                        val initialized = transcriber.initialize(modelDir, enableDiarization = true)
                        if (initialized) {
                            log.add("[成功] MeetingTranscriber 初始化成功")
                        } else {
                            log.add("[警告] MeetingTranscriber 初始化失败，将使用标准模式")
                            meetingTranscriber = null
                        }
                    }
                } else {
                    log.add("[警告] 模型未下载，无法初始化 MeetingTranscriber")
                }
            } else {
                log.add("[信息] 当前引擎不是 SherpaOnnxEngine，跳过 MeetingTranscriber")
            }
        } catch (e: Exception) {
            log.add("[警告] 初始化 MeetingTranscriber 异常: ${e.message}")
            meetingTranscriber = null
        }
    }

    /**
     * 使用 MeetingTranscriber 进行转写。
     */
    private suspend fun transcribeWithMeetingTranscriber(
        audioFile: File,
        log: MutableList<String>,
    ): MeetingTranscriptResult {
        val transcriber = meetingTranscriber!!
        val result = transcriber.transcribe(audioFile)

        return if (result.error != null) {
            log.add("[错误] MeetingTranscriber 转写错误: ${result.error}")
            MeetingTranscriptResult(error = result.error)
        } else {
            // 转换 TranscriptionResult → MeetingTranscriptResult
            MeetingTranscriptResult(
                segments = result.segments.map { seg ->
                    MeetingSegment(
                        text = seg.text,
                        startTimeMs = seg.startTimeMs,
                        endTimeMs = seg.endTimeMs,
                        speakerLabel = seg.speakerLabel,
                    )
                },
                fullText = result.fullText,
                durationMs = result.durationMs,
                hasDiarization = result.hasDiarization,
                speakers = result.segments.map { it.speakerLabel }.toSet(),
            )
        }
    }

    /**
     * 使用 AsrManager 进行转写（标准降级方案）。
     */
    private suspend fun transcribeWithAsrManager(
        audioFile: File,
        log: MutableList<String>,
    ): MeetingTranscriptResult {
        val result = asrManager.transcribeFile(audioFile.absolutePath)

        return if (result.text.isBlank()) {
            log.add("[警告] 转写结果为空")
            MeetingTranscriptResult(
                segments = emptyList(),
                fullText = "",
                durationMs = result.durationMs,
                hasDiarization = false,
                speakers = emptySet(),
                error = "转写结果为空",
            )
        } else {
            MeetingTranscriptResult(
                segments = result.segments.map { seg ->
                    MeetingSegment(
                        text = seg.text,
                        startTimeMs = seg.startTimeMs,
                        endTimeMs = seg.endTimeMs,
                        speakerLabel = "Speaker_0",
                    )
                },
                fullText = result.text,
                durationMs = result.durationMs,
                hasDiarization = false,
                speakers = setOf("Speaker_0"),
            )
        }
    }

    /**
     * 推断记忆系统路径（需与 MeetingTranscriptBridge 保持一致）。
     */
    private fun inferMemoryPath(audioFileName: String): String? {
        return if (memoryBridge != null) {
            val safeName = audioFileName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
            val datePath = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
                .format(java.util.Date())
            "${MeetingTranscriptBridge.MEMORY_PATH_PREFIX}$datePath/$safeName"
        } else {
            null
        }
    }
}
