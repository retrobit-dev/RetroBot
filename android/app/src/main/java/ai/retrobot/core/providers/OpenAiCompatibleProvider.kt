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
 * Talks to any endpoint that speaks the OpenAI chat-completions dialect.
 *
 * That covers a lot of ground with one implementation: OpenAI itself,
 * OpenRouter, Groq, Together, Mistral, DeepSeek, Fireworks, and a local
 * Ollama or LM Studio on your own machine. Everything that is not
 * OpenAI-compatible gets its own adapter.
 */
class OpenAiCompatibleProvider(
    private val id: String,
    private val displayName: String,
    private val baseUrl: String,
    private val apiKey: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val sse: SseClient,
) : ChatProvider {

    override val id: String get() = id
    override val displayName: String get() = displayName

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val base = baseUrl.trimEnd('/')

    override suspend fun listModels(): List<RemoteModel> {
        val body = sse.postJson(
            url = "$base/models",
            headers = headers(),
        )
        val list = json.decodeFromString<OpenAiModelList>(body)
        return list.data.map { model ->
            RemoteModel(
                id = model.id,
                displayName = model.name ?: model.id,
                contextWindow = model.contextLength ?: model.contextWindow,
                inputPricePerMTok = model.pricing?.prompt.asPricePerMTok(),
                outputPricePerMTok = model.pricing?.completion.asPricePerMTok(),
            )
        }
    }

    override fun stream(request: ProviderRequest): Flow<ProviderEvent> = flow {
        val payload = json.encodeToString(
            OpenAiChatRequest(
                model = request.modelId,
                messages = request.messages.map { turn ->
                    OpenAiMessage(role = turn.role.wireName(), content = turn.text)
                },
                stream = true,
                temperature = request.temperature,
                maxTokens = request.maxTokens,
                streamOptions = OpenAiStreamOptions(includeUsage = true),
            )
        )

        try {
            sse.stream(
                url = "$base/chat/completions",
                headers = headers(),
                body = payload,
            ).collect { raw ->
                val chunk = runCatching { json.decodeFromString<OpenAiChunk>(raw) }.getOrNull()
                    ?: return@collect

                chunk.usage?.let { usage ->
                    emit(
                        ProviderEvent.Usage(
                            promptTokens = usage.promptTokens,
                            outputTokens = usage.completionTokens,
                            costUsd = usage.cost,
                        )
                    )
                }

                val delta = chunk.choices.firstOrNull()?.delta
                val text = delta?.content
                if (!text.isNullOrEmpty()) emit(ProviderEvent.TextDelta(text))
            }
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
        if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
        putAll(extraHeaders)
    }
}

// ---------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------

private fun ChatTurn.Role.wireName(): String = when (this) {
    ChatTurn.Role.SYSTEM -> "system"
    ChatTurn.Role.USER -> "user"
    ChatTurn.Role.ASSISTANT -> "assistant"
}

@Serializable
private data class OpenAiMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class OpenAiStreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true,
)

@Serializable
private data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val stream: Boolean,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("stream_options") val streamOptions: OpenAiStreamOptions? = null,
)

@Serializable
private data class OpenAiDelta(
    val role: String? = null,
    val content: String? = null,
)

@Serializable
private data class OpenAiChoice(
    val index: Int = 0,
    val delta: OpenAiDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    /** OpenRouter reports the cost of the request here. */
    val cost: Double? = null,
)

@Serializable
private data class OpenAiChunk(
    val id: String = "",
    val model: String = "",
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
)

@Serializable
private data class OpenAiPricing(
    // Providers report prices either as a string ("0.0000025") or as a
    // number, so keep the raw element and parse it ourselves.
    val prompt: JsonElement? = null,
    val completion: JsonElement? = null,
)

private fun JsonElement?.asPricePerMTok(): Double? {
    val element = this ?: return null
    val raw = runCatching { element.jsonPrimitive.content }.getOrNull() ?: return null
    return raw.toDoubleOrNull()?.times(1_000_000.0)
}

@Serializable
private data class OpenAiModel(
    val id: String,
    val name: String? = null,
    @SerialName("context_length") val contextLength: Int? = null,
    @SerialName("context_window") val contextWindow: Int? = null,
    val pricing: OpenAiPricing? = null,
)

@Serializable
private data class OpenAiModelList(
    val data: List<OpenAiModel> = emptyList(),
)
