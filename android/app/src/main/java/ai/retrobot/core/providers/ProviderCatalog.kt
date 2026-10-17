package ai.retrobot.core.providers

/**
 * A provider the app knows how to talk to.
 *
 * @param dialect which wire format the provider speaks. Anything that is
 *   not OpenAI-compatible gets its own adapter.
 */
data class ProviderSpec(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val dialect: Dialect,
    val keyUrl: String,
    val keyHint: String,
    /** Extra headers the provider expects, e.g. OpenRouter's attribution. */
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Whether the app should offer a "test connection" that lists models. */
    val supportsModelListing: Boolean = true,
)

enum class Dialect {
    /** OpenAI chat completions: OpenAI, OpenRouter, Groq, Together, Mistral, DeepSeek, Ollama. */
    OPENAI_COMPATIBLE,

    /** Anthropic Messages API. */
    ANTHROPIC,

    /** Google Gemini generateContent. */
    GEMINI,
}

/**
 * The built-in provider list.
 *
 * The user can also add a custom OpenAI-compatible endpoint, which is how
 * self-hosted models (Ollama, LM Studio, vLLM) join in.
 */
object ProviderCatalog {

    val BUILT_INS: List<ProviderSpec> = listOf(
        ProviderSpec(
            id = "openrouter",
            displayName = "OpenRouter",
            defaultBaseUrl = "https://openrouter.ai/api/v1",
            dialect = Dialect.OPENAI_COMPATIBLE,
            keyUrl = "https://openrouter.ai/settings/keys",
            keyHint = "sk-or-v1-…",
            extraHeaders = mapOf(
                "HTTP-Referer" to "https://github.com/retrobit-dev/RetroBot",
                "X-OpenRouter-Title" to "RetroBot",
            ),
        ),
        ProviderSpec(
            id = "openai",
            displayName = "OpenAI",
            defaultBaseUrl = "https://api.openai.com/v1",
            dialect = Dialect.OPENAI_COMPATIBLE,
            keyUrl = "https://platform.openai.com/api-keys",
            keyHint = "sk-…",
        ),
        ProviderSpec(
            id = "anthropic",
            displayName = "Anthropic",
            defaultBaseUrl = "https://api.anthropic.com",
            dialect = Dialect.ANTHROPIC,
            keyUrl = "https://console.anthropic.com/settings/keys",
            keyHint = "sk-ant-…",
        ),
        ProviderSpec(
            id = "gemini",
            displayName = "Google Gemini",
            defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
            dialect = Dialect.GEMINI,
            keyUrl = "https://aistudio.google.com/app/apikey",
            keyHint = "AIza…",
        ),
        ProviderSpec(
            id = "groq",
            displayName = "Groq",
            defaultBaseUrl = "https://api.groq.com/openai/v1",
            dialect = Dialect.OPENAI_COMPATIBLE,
            keyUrl = "https://console.groq.com/keys",
            keyHint = "gsk_…",
        ),
        ProviderSpec(
            id = "together",
            displayName = "Together AI",
            defaultBaseUrl = "https://api.together.xyz/v1",
            dialect = Dialect.OPENAI_COMPATIBLE,
            keyUrl = "https://api.together.ai/settings/api-keys",
            keyHint = "…",
        ),
        ProviderSpec(
            id = "mistral",
            displayName = "Mistral",
            defaultBaseUrl = "https://api.mistral.ai/v1",
            dialect = Dialect.OPENAI_COMPATIBLE,
            keyUrl = "https://console.mistral.ai/api-keys",
            keyHint = "…",
        ),
        ProviderSpec(
            id = "deepseek",
            displayName = "DeepSeek",
            defaultBaseUrl = "https://api.deepseek.com/v1",
            dialect = Dialect.OPENAI_COMPATIBLE,
            keyUrl = "https://platform.deepseek.com/api_keys",
            keyHint = "sk-…",
        ),
        ProviderSpec(
            id = "ollama",
            displayName = "Ollama (local)",
            defaultBaseUrl = "http://10.0.2.2:11434/v1",
            dialect = Dialect.OPENAI_COMPATIBLE,
            keyUrl = "",
            keyHint = "no key needed",
        ),
        ProviderSpec(
            id = "custom",
            displayName = "Custom (OpenAI-compatible)",
            defaultBaseUrl = "",
            dialect = Dialect.OPENAI_COMPATIBLE,
            keyUrl = "",
            keyHint = "usually none",
        ),
    )

    fun byId(id: String): ProviderSpec? = BUILT_INS.firstOrNull { it.id == id }

    /** Every provider that can be added to the app. */
    val all: List<ProviderSpec> get() = BUILT_INS
}
