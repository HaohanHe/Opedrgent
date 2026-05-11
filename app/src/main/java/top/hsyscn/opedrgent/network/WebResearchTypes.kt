package top.hsyscn.opedrgent.network

enum class WebResearchMode {
    AUTO,
    NATIVE,
    PROVIDER,
    BROWSER,
}

data class WebResearchRequest(
    val query: String,
    val mode: WebResearchMode = WebResearchMode.AUTO,
    val allowBrowser: Boolean = false,
    val unattended: Boolean = false,
    val maxResults: Int = 5,
    val maxFetch: Int = 3,
    val allowedDomains: List<String> = emptyList(),
    val blockedDomains: List<String> = emptyList(),
)

data class WebResearchHit(
    val title: String,
    val url: String,
    val snippet: String?,
)

data class WebResearchOutcome(
    val modeUsed: WebResearchMode,
    val hits: List<WebResearchHit>,
    val fetched: List<FetchedSource>,
    val warnings: List<String>,
)

