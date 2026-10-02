package top.hsyscn.opedrgent.modelreadiness

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.llm.AvailableLocalModels
import top.hsyscn.opedrgent.llm.DownloadStatus
import top.hsyscn.opedrgent.llm.ModelDownloadManager
import top.hsyscn.opedrgent.llm.LocalModelInventory
import top.hsyscn.opedrgent.stt.ModelManager
import top.hsyscn.opedrgent.stt.ModelType
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.settings.ApiSettings

/** 端侧模型类别。 */
enum class ModelKind { LLM, ASR, TTS }

/**
 * 组件就绪状态。注意：契约固定为四态，不含 PAUSED；
 * 暂停/未完成的可断点续传进度回落为 NOT_PRESENT，并在 downloadedBytes 保留已下字节。
 */
enum class ReadyState { NOT_PRESENT, DOWNLOADING, READY, FAILED }

/** 单个模型组件的实时状态。 */
data class ComponentStatus(
    val kind: ModelKind,
    val state: ReadyState,
    val displayName: String,
    val sizeBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val speedBytesPerSec: Long = 0,
    val error: String? = null,
    val filePath: String? = null,
    /**
     * 完整性证据：通过可信 SHA-256 校验、或引擎实际成功加载才为 true；
     * 仅体积/存在性校验为 false。无上游哈希时如实保留 false。
     */
    val verified: Boolean = false,
) {
    /** 0..1；无体积信息时为 0。 */
    val progress: Float
        get() = if (sizeBytes > 0) {
            (downloadedBytes.toFloat() / sizeBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/** 单个本地模型的只读视图（目录 + 磁盘下载状态 + 本地元信息 + 启用态）。 */
data class LocalModelEntry(
    val id: String,
    val displayName: String,
    val sizeMb: Long,
    val downloaded: Boolean,
    val active: Boolean,
    val filePath: String? = null,
    val downloadedAt: Long? = null,
    val verified: Boolean = false,
)


/** 一次完整的端侧模型就绪快照。online 仅用于下载提示，不得用于本地推理门控。 */
data class ModelReadinessSnapshot(
    val llm: ComponentStatus,
    val asr: ComponentStatus,
    val tts: ComponentStatus,
    val online: Boolean = true,
    val recommendedLlmId: String? = null,
    val recommendedAsrType: String? = null,
) {
    fun of(kind: ModelKind): ComponentStatus = when (kind) {
        ModelKind.LLM -> llm
        ModelKind.ASR -> asr
        ModelKind.TTS -> tts
    }
}

/**
 * 统一的「端侧模型就绪」数据主干。
 *
 * - 不重写已有下载逻辑，仅把 LLM 的 observeProgress(SharedFlow) 与 STT 的 downloadModel(Flow)
 *   桥接进统一 snapshot；状态由真实文件（isModelDownloaded / getAllStatuses / 文件存在）
 *   与下载事件推导，不硬编码。
 * - online 仅由 ConnectivityManager.NetworkCallback 维护，只用于下载提示。
 * - 进程级单例。
 */
class ModelReadinessRepository private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val llmDownloadManager = ModelDownloadManager.getInstance(appContext)

    private val apiSettings by lazy { ApiSettings(appContext) }
    private val localModelInventory by lazy { LocalModelInventory(appContext) }

    // ── 内部可变状态（由磁盘/下载流推导） ──────────────────────────────
    @Volatile private var llmStatus: ComponentStatus =
        ComponentStatus(ModelKind.LLM, ReadyState.NOT_PRESENT, "本地大模型")
    @Volatile private var asrStatus: ComponentStatus =
        ComponentStatus(ModelKind.ASR, ReadyState.NOT_PRESENT, "离线语音识别")
    @Volatile private var ttsStatus: ComponentStatus =
        ComponentStatus(ModelKind.TTS, ReadyState.NOT_PRESENT, "系统语音合成")
    @Volatile private var online: Boolean = true
    @Volatile private var recommendedLlmId: String? = null
    @Volatile private var recommendedAsrType: String? = null

    @Volatile private var selectedLlmId: String? = null
    @Volatile private var selectedAsrType: ModelType? = null

    private val _snapshot = MutableStateFlow(buildSnapshot())
    val snapshot: StateFlow<ModelReadinessSnapshot> = _snapshot.asStateFlow()

    private var llmCollectJob: Job? = null
    private var asrCollectJob: Job? = null

    private var ttsEngine: TextToSpeech? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ── 对外操作 ──────────────────────────────────────────────────────

    /** 依据真实文件系统与下载流，重新计算推荐与各组件状态。 */
    fun refresh() {
        recommendedLlmId = pickRecommendedLlmId()
        recommendedAsrType = ModelManager.getRecommendedModel(appContext).name
        if (selectedLlmId == null) {
            // 优先恢复上次启用的本地模型 id（仅当该 id 仍在目录中）；否则回落推荐并持久化。
            val persisted = apiSettings.getActiveLocalModelId()
                ?.takeIf { AvailableLocalModels.findById(it) != null }
            selectedLlmId = persisted ?: recommendedLlmId
            selectedLlmId?.let { apiSettings.setActiveLocalModelId(it) }
        }
        if (selectedAsrType == null) selectedAsrType = ModelManager.getRecommendedModel(appContext)
        syncLlmFromDisk()
        syncAsrFromDisk()
        probeTts()
        startNetworkCallbackIfNeeded()
        emitSnapshot()
    }

    /** 启动当前推荐组件的下载/探测。 */
    fun startRecommended(kind: ModelKind) {
        when (kind) {
            ModelKind.LLM -> startLlm(recommendedLlmId ?: pickRecommendedLlmId().also { recommendedLlmId = it })
            ModelKind.ASR -> {
                val typeName = recommendedAsrType ?: ModelManager.getRecommendedModel(appContext).name
                recommendedAsrType = typeName
                startAsr(typeName)
            }
            ModelKind.TTS -> probeTts()
        }
    }

    fun startLlm(modelId: String) {
        val info = AvailableLocalModels.findById(modelId) ?: run {
            DebugLog.w(TAG, "startLlm: unknown modelId $modelId")
            return
        }
        selectedLlmId = modelId
        apiSettings.setActiveLocalModelId(modelId)
        // 先挂流再触发下载，避免错过 QUEUED/DOWNLOADING 事件（SharedFlow replay=1 可补到当前态）
        collectLlm(modelId)
        llmDownloadManager.startDownload(info)
        emitSnapshot()
    }

    /**
     * 切换当前启用（选中）的本地 LLM 模型。
     * 校验 id 在目录中存在 → 持久化启用 → 切流/按磁盘重新同步 → 发快照。
     * 切到尚未下载的模型时，就绪门控会正确落为 NOT_PRESENT（不伪造 READY）。
     */
    fun selectLlm(modelId: String): Boolean {
        AvailableLocalModels.findById(modelId) ?: return false
        apiSettings.setActiveLocalModelId(modelId)
        selectedLlmId = modelId
        llmCollectJob?.cancel()
        syncLlmFromDisk()
        emitSnapshot()
        return true
    }

    /** 只读：目录中全部本地模型的下载/启用/元信息视图（供 UI 与模型工具使用）。 */
    fun listLocalModelEntries(): List<LocalModelEntry> {
        val downloadedIds = llmDownloadManager.getDownloadedModels().map { it.id }.toSet()
        val activeId = apiSettings.getActiveLocalModelId()
        return AvailableLocalModels.MODELS.map { info ->
            val meta = localModelInventory.get(info.id)
            val file = llmDownloadManager.getModelFile(info.id)
            LocalModelEntry(
                id = info.id,
                displayName = info.displayName,
                sizeMb = info.sizeMb,
                downloaded = info.id in downloadedIds,
                active = info.id == activeId,
                filePath = file?.absolutePath,
                downloadedAt = meta?.downloadedAt,
                verified = meta?.verified ?: false,
            )
        }
    }

    /** typeName 对应 stt/ModelType 枚举名。 */
    fun startAsr(typeName: String) {
        val type = try {
            ModelType.valueOf(typeName)
        } catch (e: Exception) {
            DebugLog.w(TAG, "startAsr: unknown type $typeName")
            return
        }
        startAsrFlow(type)
    }

    fun pause(kind: ModelKind) {
        when (kind) {
            // LLM 暂停：保留 .tmp，cancel 下载 Job；状态回落为 NOT_PRESENT（保留已下字节用于续传展示）
            ModelKind.LLM -> {
                selectedLlmId?.let { llmDownloadManager.pauseDownload(it) }
                llmCollectJob?.cancel()
                syncLlmFromDisk()
            }
            // ASR 暂停：取消收集（冷 Flow 随之停止）；已下文件保留，续传时文件级跳过
            ModelKind.ASR -> {
                asrCollectJob?.cancel()
                syncAsrFromDisk()
            }
            ModelKind.TTS -> Unit
        }
        emitSnapshot()
    }

    /** 续传：LLM 凭 .tmp+Range 续传；ASR 重新收集 downloadModel（文件级跳过）。 */
    fun resume(kind: ModelKind) {
        when (kind) {
            ModelKind.LLM -> selectedLlmId?.let { startLlm(it) }
            ModelKind.ASR -> selectedAsrType?.let { startAsrFlow(it) }
            ModelKind.TTS -> probeTts()
        }
    }

    fun cancel(kind: ModelKind) {
        when (kind) {
            // LLM 取消：删除 .tmp；ASR 取消：仅停止收集（不删已落盘文件）
            ModelKind.LLM -> {
                selectedLlmId?.let { llmDownloadManager.cancelDownload(it) }
                llmCollectJob?.cancel()
                syncLlmFromDisk()
            }
            ModelKind.ASR -> {
                asrCollectJob?.cancel()
                syncAsrFromDisk()
            }
            ModelKind.TTS -> Unit
        }
        emitSnapshot()
    }

    /** 失败后重试：等价于重新触发下载。 */
    fun retry(kind: ModelKind) = resume(kind)

    /** 释放监听器与协程；不删除已下载模型文件。 */
    fun release() {
        llmCollectJob?.cancel()
        asrCollectJob?.cancel()
        networkCallback?.let { cb ->
            runCatching {
                (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
        ttsEngine?.shutdown()
        ttsEngine = null
        runCatching { scope.cancel() }
    }

    // ── 内部：LLM ─────────────────────────────────────────────────────

    private fun collectLlm(modelId: String) {
        llmCollectJob?.cancel()
        llmCollectJob = scope.launch {
            llmDownloadManager.observeProgress(modelId).collect { p ->
                val info = AvailableLocalModels.findById(modelId)
                llmStatus = when (p.status) {
                    DownloadStatus.COMPLETED -> ComponentStatus(
                        ModelKind.LLM, ReadyState.READY, info?.displayName ?: modelId,
                        sizeBytes = p.totalBytes, downloadedBytes = p.downloadedBytes,
                        speedBytesPerSec = 0, filePath = p.filePath,
                        // 走到 COMPLETED 且配置了哈希，说明已通过哈希校验
                        verified = info?.expectedSha256 != null,
                    )
                    DownloadStatus.FAILED -> ComponentStatus(
                        ModelKind.LLM, ReadyState.FAILED, info?.displayName ?: modelId,
                        sizeBytes = p.totalBytes, downloadedBytes = p.downloadedBytes,
                        speedBytesPerSec = 0, error = p.error, filePath = p.filePath,
                    )
                    DownloadStatus.CANCELLED -> ComponentStatus(
                        ModelKind.LLM, ReadyState.NOT_PRESENT, info?.displayName ?: modelId,
                        sizeBytes = p.totalBytes, downloadedBytes = p.downloadedBytes,
                        speedBytesPerSec = 0,
                    )
                    // QUEUED / DOWNLOADING / PAUSED / IDLE 统一映射为 DOWNLOADING（四态契约）
                    else -> ComponentStatus(
                        ModelKind.LLM, ReadyState.DOWNLOADING, info?.displayName ?: modelId,
                        sizeBytes = p.totalBytes, downloadedBytes = p.downloadedBytes,
                        speedBytesPerSec = p.speedBytesPerSec,
                    )
                }
                emitSnapshot()
            }
        }
    }

    private fun syncLlmFromDisk() {
        val id = selectedLlmId ?: return
        val info = AvailableLocalModels.findById(id) ?: return
        val file = llmDownloadManager.getModelFile(id)
        llmStatus = when {
            llmDownloadManager.getAllStatuses()[id] == DownloadStatus.DOWNLOADING -> {
                // 有正在进行的下载，挂流接收实时进度
                collectLlm(id)
                ComponentStatus(
                    ModelKind.LLM, ReadyState.DOWNLOADING, info.displayName,
                    sizeBytes = info.sizeMb * 1024L * 1024L,
                )
            }
            llmDownloadManager.getDownloadedModels().any { it.id == id } -> ComponentStatus(
                ModelKind.LLM, ReadyState.READY, info.displayName,
                sizeBytes = info.sizeMb * 1024L * 1024L,
                downloadedBytes = file?.length() ?: info.sizeMb * 1024L * 1024L,
                filePath = file?.absolutePath,
                verified = info.expectedSha256 != null,
            )
            else -> ComponentStatus(
                ModelKind.LLM, ReadyState.NOT_PRESENT, info.displayName,
                sizeBytes = info.sizeMb * 1024L * 1024L,
                // 保留 .tmp 已下字节，便于 UI 展示可断点续传
                downloadedBytes = llmDownloadManager.getPartialBytes(id),
            )
        }
    }

    /** 按设备总内存选择合适的 Gemma：满足 minMemoryMb 的最大者；都不满足取最小者。 */
    private fun pickRecommendedLlmId(): String {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalRamMb = if (am != null) {
            memInfo.totalMem / (1024 * 1024)
        } else {
            Runtime.getRuntime().totalMemory() / (1024 * 1024)
        }
        val eligible = AvailableLocalModels.MODELS.filter { it.minMemoryMb <= totalRamMb }
        val pick = (if (eligible.isNotEmpty()) eligible else AvailableLocalModels.MODELS)
            .maxByOrNull { it.sizeMb }
        return pick?.id ?: AvailableLocalModels.MODELS.first().id
    }

    // ── 内部：ASR ─────────────────────────────────────────────────────

    private fun startAsrFlow(type: ModelType) {
        selectedAsrType = type
        asrCollectJob?.cancel()
        val info = ModelManager.AVAILABLE_MODELS.find { it.type == type }
        asrCollectJob = scope.launch {
            ModelManager.downloadModel(appContext, type).collect { p ->
                asrStatus = when (p) {
                    is ModelManager.DownloadProgress.Downloading -> {
                        val total = info?.sizeBytes ?: 0L
                        ComponentStatus(
                            ModelKind.ASR, ReadyState.DOWNLOADING, info?.modelName ?: type.name,
                            sizeBytes = total, downloadedBytes = (p.progress * total).toLong(),
                        )
                    }
                    is ModelManager.DownloadProgress.SourceSwitch ->
                        asrStatus.copy(state = ReadyState.DOWNLOADING)
                    ModelManager.DownloadProgress.Complete -> ComponentStatus(
                        ModelKind.ASR, ReadyState.READY, info?.modelName ?: type.name,
                        sizeBytes = info?.sizeBytes ?: 0L,
                        downloadedBytes = info?.sizeBytes ?: 0L,
                        filePath = ModelManager.getModelPath(appContext, type)?.absolutePath,
                        verified = info?.fileSha256?.isNotEmpty() == true,
                    )
                    is ModelManager.DownloadProgress.Error -> ComponentStatus(
                        ModelKind.ASR, ReadyState.FAILED, info?.modelName ?: type.name,
                        sizeBytes = info?.sizeBytes ?: 0L, error = p.message,
                    )
                }
                emitSnapshot()
            }
        }
        emitSnapshot()
    }

    private fun syncAsrFromDisk() {
        val type = selectedAsrType ?: return
        val info = ModelManager.AVAILABLE_MODELS.find { it.type == type } ?: return
        asrStatus = if (ModelManager.isModelDownloaded(appContext, type)) {
            ComponentStatus(
                ModelKind.ASR, ReadyState.READY, info.modelName,
                sizeBytes = info.sizeBytes, downloadedBytes = info.sizeBytes,
                filePath = ModelManager.getModelPath(appContext, type)?.absolutePath,
                verified = info.fileSha256?.isNotEmpty() == true,
            )
        } else {
            ComponentStatus(
                ModelKind.ASR, ReadyState.NOT_PRESENT, info.modelName,
                sizeBytes = info.sizeBytes,
            )
        }
    }

    // ── 内部：TTS（系统 TextToSpeech，无需下载模型） ───────────────────

    private fun probeTts() {
        runCatching { ttsEngine?.shutdown() }
        ttsStatus = ComponentStatus(ModelKind.TTS, ReadyState.NOT_PRESENT, "系统语音合成")
        emitSnapshot()
        ttsEngine = TextToSpeech(appContext) { status ->
            ttsStatus = if (status == TextToSpeech.SUCCESS) {
                // 引擎实际初始化成功，作为可用证据
                ComponentStatus(ModelKind.TTS, ReadyState.READY, "系统语音合成", verified = true)
            } else {
                ComponentStatus(ModelKind.TTS, ReadyState.FAILED, "系统语音合成", error = "系统 TTS 引擎不可用")
            }
            emitSnapshot()
        }
    }

    // ── 内部：网络状态（仅下载提示，不参与推理门控） ──────────────────

    private fun startNetworkCallbackIfNeeded() {
        if (networkCallback != null) return
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        // 初始值：探测一次当前默认网络
        online = runCatching {
            val net = cm.activeNetwork ?: return@runCatching false
            cm.getNetworkCapabilities(net)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(true)

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
                emitSnapshot()
            }

            override fun onLost(network: Network) {
                online = false
                emitSnapshot()
            }
        }
        networkCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        emitSnapshot()
    }

    // ── 组装快照 ──────────────────────────────────────────────────────

    private fun buildSnapshot(): ModelReadinessSnapshot = ModelReadinessSnapshot(
        llm = llmStatus,
        asr = asrStatus,
        tts = ttsStatus,
        online = online,
        recommendedLlmId = recommendedLlmId,
        recommendedAsrType = recommendedAsrType,
    )

    private fun emitSnapshot() {
        _snapshot.value = buildSnapshot()
    }

    companion object {
        private const val TAG = "ModelReadiness"

        @Volatile
        private var instance: ModelReadinessRepository? = null

        /** 进程级单例，使用 applicationContext 避免泄漏 Activity。 */
        fun getInstance(context: Context): ModelReadinessRepository {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: ModelReadinessRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
