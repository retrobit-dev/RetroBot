package ai.retrobot.core.providers

import ai.retrobot.core.net.SseClient

/**
 * Builds a [ChatProvider] for a saved account.
 *
 * The account decides the dialect, the base URL and the key, so the same
 * provider can be added twice with different credentials — which is how you
 * compare a cheap model against an expensive one, or two accounts from the
 * same provider.
 */
class ProviderFactory(
    private val sse: SseClient,
) {

    fun create(spec: ProviderSpec, baseUrl: String, apiKey: String): ChatProvider {
        val url = baseUrl.ifBlank { spec.defaultBaseUrl }
        return when (spec.dialect) {
            Dialect.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(
                id = spec.id,
                displayName = spec.displayName,
                baseUrl = url,
                apiKey = apiKey,
                extraHeaders = spec.extraHeaders,
                sse = sse,
            )

            Dialect.ANTHROPIC -> AnthropicProvider(
                apiKey = apiKey,
                sse = sse,
                baseUrl = url,
            )

            Dialect.GEMINI -> GeminiProvider(
                apiKey = apiKey,
                sse = sse,
                baseUrl = url,
            )
        }
    }
}
