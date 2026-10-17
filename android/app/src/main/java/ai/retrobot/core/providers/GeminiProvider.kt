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
 * Talks to Google Gemini's generateContent API.
 *
 * Gemini renames the roles (`user` and `model` instead of `assistant`),
 * takes the system prompt as a `systemInstruction`, and reports token counts
 * under `usageMetadata` on every chunk rather than in a final usage frame.
 */
class GeminiProvider(
    private val apiKey: String,
    private val sse: SseClient,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) : ChatProvider {

    override val id: String get() = "gemini"
    override val displayName: String get() = "Google Gemini"

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
        val list = json.decodeFromString<GeminiModelList>(body)
        return list.models
            .filter { it.supportedGenerationMethods?.contains("generateContent") != false }
            .map { model ->
                RemoteModel(
                    id = model.name?.removePrefix("models/") ?: model.name.orEmpty(),
                    displayName = model.displayName ?: model.name.orEmpty(),
                    contextWindow = model.inputTokenLimit,
                )
            }
            .filter { it.id.isNotBlank() }
    }

    override fun stream(request: ProviderRequest): Flow<ProviderEvent> = flow {
        val system = request.messages
            .firstOrNull { it.role == ChatTurn.Role.SYSTEM }
            ?.text
            ?: request.systemPrompt

        val contents = request.messages
            .filter { it.role != ChatTurn.Role.SYSTEM }
            .map { turn ->
                GeminiContent(
                    role = if (turn.role == ChatTurn.Role.ASSISTANT) "model" else "user",
                    parts = listOf(GeminiPart(text = turn.text)),
                )
            }

        val payload = json.encodeToString(
            GeminiRequest(
                contents = contents,
                systemInstruction = system?.let { GeminiContent(parts = listOf(GeminiPart(text = it))) },
                generationConfig = GeminiGenerationConfig(
                    temperature = request.temperature,
                    maxOutputTokens = request.maxTokens,
                ),
            )
        )

        var promptTokens = 0
        var outputTokens = 0

        try {
            sse.stream(
                url = "$base/models/${request.modelId}:streamGenerateContent?alt=sse",
                headers = headers(),
                body = payload,
            ).collect { raw ->
                val chunk = runCatching { json.decodeFromString<GeminiChunk>(raw) }.getOrNull()
                    ?: return@collect

                chunk.usageMetadata?.let { usage ->
                    promptTokens = usage.promptTokenCount
                    outputTokens = usage.candidatesTokenCount
                }

                val text = chunk.candidates
                    ?.firstOrNull()
                    ?.content
                    ?.parts
                    ?.mapNotNull { it.text }
                    ?.joinToString("")
                if (!text.isNullOrEmpty()) emit(ProviderEvent.TextDelta(text))
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
        if (apiKey.isNotBlank()) put("x-goog-api-key", apiKey)
    }
}

// ---------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------

@Serializable
private data class GeminiPart(
    val text: String,
)

@Serializable
private data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart>,
)

@Serializable
private data class GeminiGenerationConfig(
    val temperature: Double? = null,
    @SerialName("maxOutputTokens") val maxOutputTokens: Int? = null,
)

@Serializable
private data class GeminiRequest(
    val contents: List<GeminiContent>,
    @SerialName("systemInstruction") val systemInstruction: GeminiContent? = null,
    @SerialName("generationConfig") val generationConfig: GeminiGenerationConfig? = null,
)

@Serializable
private data class GeminiUsage(
    @SerialName("promptTokenCount") val promptTokenCount: Int = 0,
    @SerialName("candidatesTokenCount") val candidatesTokenCount: Int = 0,
    @SerialName("totalTokenCount") val totalTokenCount: Int = 0,
)

@Serializable
private data class GeminiCandidate(
    val content: GeminiContent? = null,
    @SerialName("finishReason") val finishReason: String? = null,
)

@Serializable
private data class GeminiChunk(
    val candidates: List<GeminiCandidate>? = null,
    @SerialName("usageMetadata") val usageMetadata: GeminiUsage? = null,
)

@Serializable
private data class GeminiModel(
    val name: String? = null,
    @SerialName("displayName") val displayName: String? = null,
    @SerialName("inputTokenLimit") val inputTokenLimit: Int? = null,
    @SerialName("supportedGenerationMethods") val supportedGenerationMethods: List<String>? = null,
)

@Serializable
private data class GeminiModelList(
    val models: List<GeminiModel> = emptyList(),
)
