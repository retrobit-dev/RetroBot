package ai.retrobot.core.arena

import ai.retrobot.core.providers.ChatProvider
import ai.retrobot.core.providers.ChatTurn
import ai.retrobot.core.providers.ProviderEvent
import ai.retrobot.core.providers.ProviderRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Which side of the battle a piece of output belongs to. */
enum class Side { LEFT, RIGHT }

/** One model taking part in a battle. */
data class BattleParticipant(
    /** Stable identifier of the model in the registry. */
    val key: String,
    /** Name shown after the vote is revealed. */
    val displayName: String,
    /** The provider that serves it. */
    val providerId: String,
    /** The provider's own id for the model. */
    val modelId: String,
    /** The account (provider + credential) that reaches it. */
    val accountId: String,
)

/** Progress of a battle. */
sealed interface BattleEvent {
    /** A piece of an answer. */
    data class Delta(val side: Side, val text: String) : BattleEvent

    /** Token counts and cost for one side. */
    data class Usage(val side: Side, val usage: ProviderEvent.Usage) : BattleEvent

    /** One side failed. The other side keeps going. */
    data class Failed(val side: Side, val message: String) : BattleEvent

    /** Both sides are done. */
    data object Completed : BattleEvent
}

/**
 * Runs a battle: the same prompt to both models, answered at the same time.
 *
 * The two sides run concurrently so the wall-clock time is that of the slower
 * model rather than the sum of both. A failure on one side is reported but
 * never cancels the other.
 */
class BattleEngine {

    suspend fun run(
        prompt: String,
        systemPrompt: String?,
        left: Pair<ChatProvider, BattleParticipant>,
        right: Pair<ChatProvider, BattleParticipant>,
        temperature: Double? = null,
        maxTokens: Int? = null,
        onEvent: (BattleEvent) -> Unit,
    ) = coroutineScope {
        val (leftProvider, leftParticipant) = left
        val (rightProvider, rightParticipant) = right

        val messages = buildList {
            systemPrompt?.takeIf { it.isNotBlank() }
                ?.let { add(ChatTurn(ChatTurn.Role.SYSTEM, it)) }
            add(ChatTurn(ChatTurn.Role.USER, prompt))
        }

        val leftJob = launch {
            streamSide(
                provider = leftProvider,
                participant = leftParticipant,
                side = Side.LEFT,
                messages = messages,
                temperature = temperature,
                maxTokens = maxTokens,
                onEvent = onEvent,
            )
        }

        val rightJob = launch {
            streamSide(
                provider = rightProvider,
                participant = rightParticipant,
                side = Side.RIGHT,
                messages = messages,
                temperature = temperature,
                maxTokens = maxTokens,
                onEvent = onEvent,
            )
        }

        leftJob.join()
        rightJob.join()
        onEvent(BattleEvent.Completed)
    }

    private suspend fun streamSide(
        provider: ChatProvider,
        participant: BattleParticipant,
        side: Side,
        messages: List<ChatTurn>,
        temperature: Double?,
        maxTokens: Int?,
        onEvent: (BattleEvent) -> Unit,
    ) {
        val request = ProviderRequest(
            modelId = participant.modelId,
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
        )

        provider.stream(request).collect { event ->
            when (event) {
                is ProviderEvent.TextDelta -> onEvent(BattleEvent.Delta(side, event.text))
                is ProviderEvent.Usage -> onEvent(BattleEvent.Usage(side, event.usage))
                is ProviderEvent.Failed -> onEvent(BattleEvent.Failed(side, event.message))
                ProviderEvent.Done -> Unit
            }
        }
    }
}
