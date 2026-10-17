package ai.retrobot.feature.chat

import ai.retrobot.core.providers.ChatTurn
import ai.retrobot.core.providers.ProviderEvent
import ai.retrobot.core.providers.ProviderRequest
import ai.retrobot.data.ArenaRepository
import ai.retrobot.data.ModelEntity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class UiMessage(
    val id: String,
    val role: String,
    val content: String,
    val isStreaming: Boolean = false,
    val error: String? = null,
)

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val input: String = "",
    val isBusy: Boolean = false,
    val selectedModel: ModelEntity? = null,
    val models: List<ModelEntity> = emptyList(),
    val error: String? = null,
)

/**
 * A plain one-to-one chat with a single model.
 *
 * This is the "Direct" mode an arena app offers: no comparison, no votes,
 * just a conversation with whichever model is selected.
 */
class ChatViewModel(
    private val repository: ArenaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val history = mutableListOf<ChatTurn>()
    private var chatJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeModels().collect { models ->
                _state.update { current ->
                    current.copy(
                        models = models,
                        selectedModel = current.selectedModel
                            ?: models.firstOrNull(),
                    )
                }
            }
        }
    }

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value) }
    }

    fun selectModel(model: ModelEntity) {
        if (_state.value.selectedModel?.key == model.key) return
        // Switching model starts a fresh conversation: the old turns were
        // produced by a different model and would confuse the new one.
        history.clear()
        chatJob?.cancel()
        _state.update {
            it.copy(
                selectedModel = model,
                messages = emptyList(),
                isBusy = false,
                error = null,
            )
        }
    }

    fun clearConversation() {
        chatJob?.cancel()
        history.clear()
        _state.update { it.copy(messages = emptyList(), error = null) }
    }

    fun send() {
        val text = _state.value.input.trim()
        val model = _state.value.selectedModel
        if (text.isEmpty() || model == null || _state.value.isBusy) return

        history += ChatTurn(ChatTurn.Role.USER, text)
        val userMessage = UiMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = text,
        )
        val assistantId = UUID.randomUUID().toString()

        _state.update {
            it.copy(
                messages = it.messages + userMessage + UiMessage(
                    id = assistantId,
                    role = "assistant",
                    content = "",
                    isStreaming = true,
                ),
                input = "",
                isBusy = true,
                error = null,
            )
        }

        chatJob = viewModelScope.launch {
            val provider = repository.providerFor(model.accountId)
            if (provider == null) {
                _state.update {
                    it.copy(
                        isBusy = false,
                        error = "Could not reach the provider for ${model.displayName}.",
                    )
                }
                return@launch
            }

            provider.stream(
                ProviderRequest(
                    modelId = model.modelId,
                    messages = history.toList(),
                )
            ).collect { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> _state.update { current ->
                        current.copy(
                            messages = current.messages.map { message ->
                                if (message.id == assistantId) {
                                    message.copy(content = message.content + event.text)
                                } else {
                                    message
                                }
                            },
                        )
                    }

                    is ProviderEvent.Failed -> _state.update { current ->
                        current.copy(
                            messages = current.messages.map { message ->
                                if (message.id == assistantId) {
                                    message.copy(isStreaming = false, error = event.message)
                                } else {
                                    message
                                }
                            },
                        )
                    }

                    is ProviderEvent.Usage, ProviderEvent.Done -> Unit
                }
            }

            _state.update { current ->
                current.copy(
                    isBusy = false,
                    messages = current.messages.map { message ->
                        if (message.id == assistantId) message.copy(isStreaming = false) else message
                    },
                )
            }
        }
    }

    fun stop() {
        chatJob?.cancel()
        chatJob = null
        _state.update { it.copy(isBusy = false) }
    }

    companion object {
        fun factory(repository: ArenaRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(repository) }
        }
    }
}
