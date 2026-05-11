package top.hsyscn.opedrgent.automation

import java.util.UUID

enum class AutomationKind {
    HEARTBEAT_NOTES,
    RUN_PROMPT,
}

data class Automation(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean,
    val intervalMinutes: Long,
    val kind: AutomationKind,
    val targetSessionId: String?,
    val prompt: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

