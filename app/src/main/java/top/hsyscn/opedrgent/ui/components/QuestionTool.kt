package top.hsyscn.opedrgent.ui.components

import java.util.UUID

data class QuestionOption(
    val label: String,
    val description: String = "",
)

data class QuestionInfo(
    val question: String,
    val header: String = "",
    val options: List<QuestionOption>,
    val multiple: Boolean = false,
    val allowCustom: Boolean = false,
)

data class QuestionRequest(
    val id: String = UUID.randomUUID().toString(),
    val questions: List<QuestionInfo>,
)

data class ConfirmationOption(
    val label: String,
    val description: String = "",
)

data class ConfirmationRequest(
    val id: String = UUID.randomUUID().toString(),
    val message: String,
    val detail: String = "",
    val options: List<ConfirmationOption> = emptyList(),
    val timeoutSeconds: Int = 30,
)
