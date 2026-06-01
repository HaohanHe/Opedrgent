package top.hsyscn.opedrgent.utils

enum class Platform {
    ANDROID,
    CLI,
    API,
    WEB
}

data class PlatformContext(
    val platform: Platform = Platform.ANDROID,
    val hasTTS: Boolean = false,
    val hasVoiceInput: Boolean = false,
    val hasLocation: Boolean = false,
    val hasBrowser: Boolean = false,
    val hasCalendar: Boolean = false,
    val hasStt: Boolean = false,
    val screenInfo: String? = null,
)
