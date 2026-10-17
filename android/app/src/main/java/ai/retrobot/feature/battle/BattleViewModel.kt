package ai.retrobot.feature.battle

import ai.retrobot.core.arena.BattleEngine
import ai.retrobot.core.arena.BattleEvent
import ai.retrobot.core.arena.Side
import ai.retrobot.core.security.SettingsStore
import ai.retrobot.data.ArenaRepository
import ai.retrobot.data.ModelEntity
import ai.retrobot.data.VoteEntity
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
import kotlin.random.Random

/** Which slot the user is picking a model for. */
enum class Slot { A, B }

data class BattleUiState(
    val prompt: String = "",
    /** The model the user picked for slot A. */
    val slotA: ModelEntity? = null,
    /** The model the user picked for slot B. */
    val slotB: ModelEntity? = null,
    /** The model actually answering in the left pane. */
    val paneLeft: ModelEntity? = null,
    /** The model actually answering in the right pane. */
    val paneRight: ModelEntity? = null,
    val leftText: String = "",
    val rightText: String = "",
    val leftError: String? = null,
    val rightError: String? = null,
    val leftCostUsd: Double? = null,
    val rightCostUsd: Double? = null,
    val isRunning: Boolean = false,
    val hasVoted: Boolean = false,
    /** True once the identities behind the two panes are shown. */
    val revealed: Boolean = false,
    val models: List<ModelEntity> = emptyList(),
    val message: String? = null,
    val totalVotes: Int = 0,
    /** The parameters the next battle will run with. */
    val temperature: Double = 0.7,
    val maxTokens: Int = 2048,
    val systemPrompt: String = "",
) {
    /** True when the two panes hold different models and a battle can start. */
    val canStart: Boolean
        get() = prompt.isNotBlank() &&
            slotA != null &&
            slotB != null &&
            slotA.key != slotB.key &&
            !isRunning
}

/**
 * Drives the battle screen.
 *
 * The blind part lives here: the user picks a model for slot A and one for
 * slot B, and a coin flip decides which of them ends up in which pane. The
 * panes are labelled by position, never by model, until a vote is cast.
 */
class BattleViewModel(
    private val repository: ArenaRepository,
    private val battleEngine: BattleEngine,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(BattleUiState())
    val state: StateFlow<BattleUiState> = _state.asStateFlow()

    private var battleJob: Job? = null

    init {
        _state.update {
            it.copy(
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                systemPrompt = settings.battleSystemPrompt,
            )
        }
        viewModelScope.launch {
            repository.observeModels().collect { models ->
                _state.update { current ->
                    current.copy(
                        models = models,
                        // Keep the picks valid if a model was removed.
                        slotA = current.slotA?.takeIf { it.key in models.map(ModelEntity::key) },
                        slotB = current.slotB?.takeIf { it.key in models.map(ModelEntity::key) },
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.voteCount().let { count ->
                _state.update { it.copy(totalVotes = count) }
            }
        }
    }

    fun onPromptChange(value: String) {
        _state.update { it.copy(prompt = value) }
    }

    fun onTemperatureChange(value: Double) {
        settings.temperature = value
        _state.update { it.copy(temperature = value) }
    }

    fun onMaxTokensChange(value: Int) {
        settings.maxTokens = value.coerceIn(128, 32_768)
        _state.update { it.copy(maxTokens = settings.maxTokens) }
    }

    fun onSystemPromptChange(value: String) {
        settings.battleSystemPrompt = value
        _state.update { it.copy(systemPrompt = value) }
    }

    fun selectModel(slot: Slot, model: ModelEntity) {
        _state.update { current ->
            when (slot) {
                Slot.A -> current.copy(slotA = model, message = null)
                Slot.B -> current.copy(slotB = model, message = null)
            }
        }
    }

    /** Run a battle between the two chosen models. */
    fun start() {
        val current = _state.value
        val prompt = current.prompt.trim()
        val slotA = current.slotA
        val slotB = current.slotB

        if (prompt.isEmpty() || slotA == null || slotB == null) return
        if (slotA.key == slotB.key) {
            _state.update { it.copy(message = "Pick two different models.") }
            return
        }

        battleJob?.cancel()

        // The blind assignment: a coin flip decides which chosen model lands
        // in which pane.
        val swapped = Random.nextBoolean()
        val paneLeft = if (swapped) slotB else slotA
        val paneRight = if (swapped) slotA else slotB

        _state.update {
            it.copy(
                paneLeft = paneLeft,
                paneRight = paneRight,
                leftText = "",
                rightText = "",
                leftError = null,
                rightError = null,
                leftCostUsd = null,
                rightCostUsd = null,
                isRunning = true,
                hasVoted = false,
                revealed = false,
                message = null,
            )
        }

        battleJob = viewModelScope.launch {
            val leftProvider = repository.providerFor(paneLeft.accountId)
            val rightProvider = repository.providerFor(paneRight.accountId)

            if (leftProvider == null || rightProvider == null) {
                _state.update {
                    it.copy(
                        isRunning = false,
                        message = "Could not reach the provider for one of the models. " +
                            "Check the key in Settings.",
                    )
                }
                return@launch
            }

            battleEngine.run(
                prompt = prompt,
                systemPrompt = settings.battleSystemPrompt,
                left = leftProvider to repository.participantFor(paneLeft),
                right = rightProvider to repository.participantFor(paneRight),
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
            ) { event ->
                when (event) {
                    is BattleEvent.Delta -> _state.update { current ->
                        when (event.side) {
                            Side.LEFT -> current.copy(leftText = current.leftText + event.text)
                            Side.RIGHT -> current.copy(rightText = current.rightText + event.text)
                        }
                    }

                    is BattleEvent.Usage -> _state.update { current ->
                        when (event.side) {
                            Side.LEFT -> current.copy(leftCostUsd = event.usage.costUsd)
                            Side.RIGHT -> current.copy(rightCostUsd = event.usage.costUsd)
                        }
                    }

                    is BattleEvent.Failed -> _state.update { current ->
                        when (event.side) {
                            Side.LEFT -> current.copy(leftError = event.message)
                            Side.RIGHT -> current.copy(rightError = event.message)
                        }
                    }

                    BattleEvent.Completed -> Unit
                }
            }

            _state.update { it.copy(isRunning = false) }
        }
    }

    /** Stop the running battle. */
    fun stop() {
        battleJob?.cancel()
        battleJob = null
        _state.update { it.copy(isRunning = false) }
    }

    /**
     * Cast the vote and reveal who answered what.
     *
     * @param winner which pane won: "left", "right" or "tie".
     */
    fun vote(winner: String) {
        val current = _state.value
        val left = current.paneLeft ?: return
        val right = current.paneRight ?: return

        if (current.hasVoted) return

        val vote = VoteEntity(
            id = UUID.randomUUID().toString(),
            prompt = current.prompt.trim(),
            leftModelKey = left.key,
            rightModelKey = right.key,
            winner = winner,
            leftResponse = current.leftText,
            rightResponse = current.rightText,
            leftCostUsd = current.leftCostUsd,
            rightCostUsd = current.rightCostUsd,
        )

        viewModelScope.launch {
            repository.recordVote(vote)
            val count = repository.voteCount()
            _state.update {
                it.copy(
                    hasVoted = true,
                    revealed = true,
                    totalVotes = count,
                )
            }
        }
    }

    /** Clear the answers and start over, keeping the model picks. */
    fun reset() {
        battleJob?.cancel()
        battleJob = null
        _state.update {
            it.copy(
                paneLeft = null,
                paneRight = null,
                leftText = "",
                rightText = "",
                leftError = null,
                rightError = null,
                leftCostUsd = null,
                rightCostUsd = null,
                isRunning = false,
                hasVoted = false,
                revealed = false,
                message = null,
            )
        }
    }

    companion object {
        fun factory(
            repository: ArenaRepository,
            battleEngine: BattleEngine,
            settings: SettingsStore,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { BattleViewModel(repository, battleEngine, settings) }
        }
    }
}
