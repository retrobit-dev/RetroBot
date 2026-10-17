package ai.retrobot.core.providers

import ai.retrobot.core.net.ProviderHttpException
import ai.retrobot.core.net.SseClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Talks to Anthropic's Messages API.
 *
 * The dialect differs from OpenAI in three ways that matter here:
 *  - the system prompt is a top-level field, not a message
 *  - messages only carry the `user` and `assistant` roles
 *  - the stream uses named events, with token counts split across
 *    `message_start` and `message_delta`
 */
class AnthropicProvider(
    private val apiKey: String,
    private val sse: SseClient,
    private val baseUrl: String = "https://api.anthropic.com",
    private val apiVersion: String = "2023-06-01",
) : ChatProvider {

    override val id: String get() = "anthropic"
    override val displayName: String get() = "Anthropic"

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val base = baseUrl.trimEnd('/')

    override suspend fun listModels(): List<RemoteModel> {
        val body = sse.postJson(
            url = "$base/v1/models",
            headers = headers(),
        )
        val list = json.decodeFromString<AnthropicModelList>(body)
        return list.data.map { RemoteModel(id = it.id, displayName = it.displayName ?: it.id) }
    }

    override fun stream(request: ProviderRequest): Flow<ProviderEvent> = flow {
        val system = request.messages
            .firstOrNull { it.role == ChatTurn.Role.SYSTEM }
            ?.text
            ?: request.systemPrompt

        val conversation = request.messages
            .filter { it.role != ChatTurn.Role.SYSTEM }
            .map { AnthropicMessage(role = it.role.wireName(), content = it.text) }

        val payload = json.encodeToString(
            AnthropicRequest(
                model = request.modelId,
                maxTokens = request.maxTokens ?: DEFAULT_MAX_TOKENS,
                system = system,
                messages = conversation,
                temperature = request.temperature,
                stream = true,
            )
        )

        var promptTokens = 0
        var outputTokens = 0

        try {
            sse.stream(
                url = "$base/v1/messages",
                headers = headers(),
                body = payload,
            ).collect { raw ->
                val event = runCatching { json.decodeFromString<AnthropicEvent>(raw) }.getOrNull()
                    ?: return@collect

                when (event.type) {
                    "message_start" -> {
                        promptTokens = event.message?.usage?.inputTokens ?: 0
                    }

                    "content_block_delta" -> {
                        event.delta?.text?.takeIf { it.isNotEmpty() }
                            ?.let { emit(ProviderEvent.TextDelta(it)) }
                    }

                    "message_delta" -> {
                        outputTokens = event.usage?.outputTokens ?: outputTokens
                    }

                    "error" -> {
                        val message = event.error?.message ?: "Anthropic reported an error"
                        emit(ProviderEvent.Failed(message))
                    }
                }
            }

            emit(
                ProviderEvent.Usage(
                    promptTokens = promptTokens,
                    outputTokens = outputTokens,
                )
            )
            emit(ProviderEvent.Done)
        } catch (e: ProviderHttpException) {
            emit(ProviderEvent.Failed("${e.message} — ${e.hint}"))
        } catch (e: CancellationException) {
            // A cancelled battle is not a provider failure.
            throw e
        } catch (e: Exception) {
            emit(ProviderEvent.Failed(e.message ?: "The request failed"))
        }
    }

    private fun headers(): Map<String, String> = buildMap {
        if (apiKey.isNotBlank()) put("x-api-key", apiKey)
        put("anthropic-version", apiVersion)
    }

    private companion object {
        const val DEFAULT_MAX_TOKENS = 4096
    }
}

private fun ChatTurn.Role.wireName(): String = when (this) {
    ChatTurn.Role.SYSTEM -> "user" // filtered out before this is reached
    ChatTurn.Role.USER -> "user"
    ChatTurn.Role.ASSISTANT -> "assistant"
}

// ---------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------

@Serializable
private data class AnthropicMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String? = null,
    val messages: List<AnthropicMessage>,
    val temperature: Double? = null,
    val stream: Boolean = true,
)

@Serializable
private data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
)

@Serializable
private data class AnthropicDelta(
    val type: String? = null,
    val text: String? = null,
)

@Serializable
private data class AnthropicError(
    val type: String? = null,
    val message: String? = null,
)

@Serializable
private data class AnthropicMessageStart(
    val usage: AnthropicUsage? = null,
)

@Serializable
private data class AnthropicEvent(
    val type: String = "",
    val message: AnthropicMessageStart? = null,
    val delta: AnthropicDelta? = null,
    val usage: AnthropicUsage? = null,
    val error: AnthropicError? = null,
)

@Serializable
private data class AnthropicModel(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
private data class AnthropicModelList(
    val data: List<AnthropicModel> = emptyList(),
)
