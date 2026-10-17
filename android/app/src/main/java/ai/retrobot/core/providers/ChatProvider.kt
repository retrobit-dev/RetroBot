package ai.retrobot.core.providers

import kotlinx.coroutines.flow.Flow

/**
 * One turn of a conversation, in a provider-neutral shape.
 */
data class ChatTurn(
    val role: Role,
    val text: String,
) {
    enum class Role { SYSTEM, USER, ASSISTANT }
}

/** A request for a completion. */
data class ProviderRequest(
    val modelId: String,
    val messages: List<ChatTurn>,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val systemPrompt: String? = null,
)

/** Everything a provider can tell us while it is working. */
sealed interface ProviderEvent {
    /** A piece of the answer. */
    data class TextDelta(val text: String) : ProviderEvent

    /** Token counts, and cost when the provider reports it. */
    data class Usage(
        val promptTokens: Int = 0,
        val outputTokens: Int = 0,
        val costUsd: Double? = null,
    ) : ProviderEvent

    /** The provider gave up. */
    data class Failed(val message: String) : ProviderEvent

    /** The answer is complete. */
    data object Done : ProviderEvent
}

/** A model the provider offers. */
data class RemoteModel(
    val id: String,
    val displayName: String = id,
    val contextWindow: Int? = null,
    val inputPricePerMTok: Double? = null,
    val outputPricePerMTok: Double? = null,
)

/**
 * A source of completions.
 *
 * The app is built around this interface, so adding a provider means adding
 * one implementation and one entry in [ProviderCatalog] — nothing else in
 * the app changes.
 */
interface ChatProvider {

    /** Stable id, e.g. `openai`. */
    val id: String

    /** Name shown to the user. */
    val displayName: String

    /** List the models this account can reach. */
    suspend fun listModels(): List<RemoteModel>

    /**
     * Stream a completion.
     *
     * The flow completes when the answer is finished. Failures are reported
     * as [ProviderEvent.Failed] rather than thrown, so one side of a battle
     * failing does not take down the other side.
     */
    fun stream(request: ProviderRequest): Flow<ProviderEvent>
}
