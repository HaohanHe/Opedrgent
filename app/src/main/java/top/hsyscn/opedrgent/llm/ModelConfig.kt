package top.hsyscn.opedrgent.llm

data class ConfigKey(val id: String, val label: String)

object ConfigKeys {
    val MAX_TOKENS = ConfigKey("max_tokens", "Max Tokens")
    val MAX_CONTEXT_LENGTH = ConfigKey("max_context_length", "Context Length")
    val TOPK = ConfigKey("topk", "TopK")
    val TOPP = ConfigKey("topp", "TopP")
    val TEMPERATURE = ConfigKey("temperature", "Temperature")
    val ACCELERATOR = ConfigKey("accelerator", "Accelerator")
    val VISION_ACCELERATOR = ConfigKey("vision_accelerator", "Vision Backend")
    val AUDIO_ACCELERATOR = ConfigKey("audio_accelerator", "Audio Backend")
    val ENABLE_THINKING = ConfigKey("enable_thinking", "Enable Thinking")
    val ENABLE_SPECULATIVE_DECODING = ConfigKey("enable_speculative_decoding", "SpecDec")
}

enum class ConfigEditorType {
    LABEL,
    NUMBER_SLIDER,
    BOOLEAN_SWITCH,
    SEGMENTED_BUTTON,
}

enum class ConfigValueType {
    INT,
    FLOAT,
    STRING,
    BOOLEAN,
}

open class Config(
    val type: ConfigEditorType,
    open val key: ConfigKey,
    open val defaultValue: Any,
    open val valueType: ConfigValueType,
    open val needReinitialization: Boolean = true,
)

class NumberSliderConfig(
    override val key: ConfigKey,
    val sliderMin: Float,
    val sliderMax: Float,
    override val defaultValue: Float,
    override val valueType: ConfigValueType,
    override val needReinitialization: Boolean = true,
) : Config(
    type = ConfigEditorType.NUMBER_SLIDER,
    key = key,
    defaultValue = defaultValue,
    valueType = valueType,
    needReinitialization = needReinitialization,
)

class BooleanSwitchConfig(
    override val key: ConfigKey,
    override val defaultValue: Boolean,
    override val needReinitialization: Boolean = true,
) : Config(
    type = ConfigEditorType.BOOLEAN_SWITCH,
    key = key,
    defaultValue = defaultValue,
    valueType = ConfigValueType.BOOLEAN,
    needReinitialization = needReinitialization,
)

class SegmentedButtonConfig(
    override val key: ConfigKey,
    override val defaultValue: String,
    val options: List<String>,
    override val needReinitialization: Boolean = true,
) : Config(
    type = ConfigEditorType.SEGMENTED_BUTTON,
    key = key,
    defaultValue = defaultValue,
    valueType = ConfigValueType.STRING,
    needReinitialization = needReinitialization,
)

fun LocalModelInfo.computeConfigs(): List<Config> {
    val configs = mutableListOf<Config>()

    configs.add(
        NumberSliderConfig(
            key = ConfigKeys.MAX_TOKENS,
            sliderMin = 512f,
            sliderMax = maxContextLength.toFloat().coerceAtMost(32000f),
            defaultValue = maxTokens.toFloat(),
            valueType = ConfigValueType.INT,
        )
    )

    configs.add(
        NumberSliderConfig(
            key = ConfigKeys.TOPK,
            sliderMin = 5f,
            sliderMax = 100f,
            defaultValue = 64f,
            valueType = ConfigValueType.INT,
        )
    )

    configs.add(
        NumberSliderConfig(
            key = ConfigKeys.TOPP,
            sliderMin = 0f,
            sliderMax = 1f,
            defaultValue = 0.95f,
            valueType = ConfigValueType.FLOAT,
        )
    )

    configs.add(
        NumberSliderConfig(
            key = ConfigKeys.TEMPERATURE,
            sliderMin = 0f,
            sliderMax = 2f,
            defaultValue = 0.7f,
            valueType = ConfigValueType.FLOAT,
        )
    )

    val backendOptions = if (preferGpu) listOf("GPU", "CPU") else listOf("CPU")
    configs.add(
        SegmentedButtonConfig(
            key = ConfigKeys.ACCELERATOR,
            defaultValue = backendOptions.first(),
            options = backendOptions,
            needReinitialization = true,
        )
    )

    if (supportsThinking) {
        configs.add(
            BooleanSwitchConfig(
                key = ConfigKeys.ENABLE_THINKING,
                defaultValue = false,
            )
        )
    }

    if (supportsSpecDec) {
        configs.add(
            BooleanSwitchConfig(
                key = ConfigKeys.ENABLE_SPECULATIVE_DECODING,
                defaultValue = false,
            )
        )
    }

    return configs
}

fun computeInferenceConfigFromOverrides(
    base: LocalModelInfo,
    overrides: Map<String, Any>,
): LlmInferenceConfig {
    val backend = when (val acc = overrides[ConfigKeys.ACCELERATOR.id]?.toString()) {
        "GPU" -> com.google.ai.edge.litertlm.Backend.GPU()
        else -> com.google.ai.edge.litertlm.Backend.CPU()
    }
    val maxTokens = (overrides[ConfigKeys.MAX_TOKENS.id] as? Number)?.toInt() ?: base.maxTokens
    val topK = (overrides[ConfigKeys.TOPK.id] as? Number)?.toInt() ?: 64
    val topP = (overrides[ConfigKeys.TOPP.id] as? Number)?.toFloat() ?: 0.95f
    val temperature = (overrides[ConfigKeys.TEMPERATURE.id] as? Number)?.toFloat() ?: 0.7f
    val enableThinking = overrides[ConfigKeys.ENABLE_THINKING.id] as? Boolean ?: base.supportsThinking
    val enableSpecDec = overrides[ConfigKeys.ENABLE_SPECULATIVE_DECODING.id] as? Boolean ?: base.supportsSpecDec

    return LlmInferenceConfig(
        backend = backend,
        maxContextLength = base.maxContextLength,
        maxTokens = maxTokens.coerceAtMost(base.maxContextLength / 2),
        temperature = temperature,
        topK = topK,
        topP = topP,
        enableThinking = enableThinking,
        enableSpeculativeDecoding = enableSpecDec,
        supportsImage = base.supportsImage,
        supportsAudio = base.supportsAudio,
    )
}