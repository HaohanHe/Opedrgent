package top.hsyscn.opedrgent.ui.state

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import top.hsyscn.opedrgent.ui.components.ConfirmationRequest
import top.hsyscn.opedrgent.ui.components.QuestionRequest

/**
 * Agent 与 UI 之间的双向交互状态管理器。
 *
 * 集中管理 Agent 向用户发起的提问（ask_question）和确认（ask_confirmation）请求，
 * 以及用户回应后向 Agent 回传结果的事件通道。
 */
class AgentUiStateManager {

    private val _questionRequest = MutableStateFlow<QuestionRequest?>(null)
    val questionRequest: StateFlow<QuestionRequest?> = _questionRequest

    private val _questionResponse = MutableSharedFlow<List<List<String>>>(replay = 0)
    val questionResponse: SharedFlow<List<List<String>>> = _questionResponse

    private val _confirmationRequest = MutableStateFlow<ConfirmationRequest?>(null)
    val confirmationRequest: StateFlow<ConfirmationRequest?> = _confirmationRequest

    private val _confirmationResponse = MutableSharedFlow<String?>(replay = 0)
    val confirmationResponse: SharedFlow<String?> = _confirmationResponse

    fun setQuestionRequest(request: QuestionRequest?) {
        _questionRequest.value = request
    }

    fun setConfirmationRequest(request: ConfirmationRequest?) {
        _confirmationRequest.value = request
    }

    fun respondToQuestion(answers: List<List<String>>) {
        _questionRequest.value = null
        _questionResponse.tryEmit(answers)
    }

    fun respondToConfirmation(selectedOption: String?) {
        _confirmationRequest.value = null
        _confirmationResponse.tryEmit(selectedOption)
    }
}
