package top.hsyscn.opedrgent.cultivation.mirror

import android.content.Context
import top.hsyscn.opedrgent.llm.LocalLlmEngine
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.settings.ApiSettings

/**
 * 修炼统一运行时（逻辑重设计 P0）。
 *
 * 把“端侧 / 云端后端选择 + 端侧就绪检查 + 云端 Key 校验”从 UI 状态层下沉为一个能力层，
 * 取代原先散落在 CultivationStateManager.analyze 与 resolveBackend 里两份重复实现。
 *
 * 隐私纪律不变：默认端侧、全程不出设备；只有用户显式打开云端开关、且已配置 API Key 时才构造云端后端，
 * 条件不满足时返回确定性的 [Resolution.Unavailable]，不抛异常、也不静默降级到云端。
 */
class MirrorRuntime(
    private val app: Context,
    private val apiSettings: ApiSettings,
) {
    private val localBackend: MirrorLlmBackend by lazy { LocalMirrorBackend(app) }

    sealed interface Resolution {
        /** 后端可用，[backend] 为本次会话使用的端侧或云端后端。 */
        data class Ready(val backend: MirrorLlmBackend) : Resolution

        /** 后端不可用，[reason] 为可直接展示给用户的确定性原因。 */
        data class Unavailable(val reason: String) : Resolution
    }

    /** 端侧模型是否已加载就绪。 */
    fun localReady(): Boolean =
        runCatching { LocalLlmEngine.getInstance(app).isReady }.getOrDefault(false)

    /** 当前加载的端侧模型标签，未加载时返回 null。 */
    fun localModelId(): String? =
        runCatching { LocalLlmEngine.getInstance(app).currentModelId }.getOrNull()

    /**
     * 按用户选择解析本次使用的后端。
     *
     * @param useCloud 是否显式选择云端；false 走端侧本地引擎
     */
    fun resolve(useCloud: Boolean): Resolution =
        if (useCloud) {
            val config = apiSettings.getApiConfig()
            if (config == null || config.apiKey.isBlank()) {
                Resolution.Unavailable("未配置云端 API Key，已阻止上传；请改用端侧或先在设置配置。")
            } else {
                Resolution.Ready(RemoteMirrorBackend(config, LlmClient()))
            }
        } else {
            if (!localReady()) {
                Resolution.Unavailable("端侧模型尚未加载，请先在设置/录音中下载并加载本地模型。")
            } else {
                Resolution.Ready(localBackend)
            }
        }
}
