package ai.retrobot.core.security

import android.content.Context
import android.content.SharedPreferences

/**
 * Non-secret settings.
 *
 * These are deliberately kept in plain preferences: nothing here is a
 * credential, and encrypting them would only add a keystore round trip on
 * every read. API keys live in [KeyVault] instead.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("retrobot_settings", Context.MODE_PRIVATE)

    /** The prompt prepended to every battle, so the comparison is fair. */
    var battleSystemPrompt: String
        get() = prefs.getString(KEY_BATTLE_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT).orEmpty()
        set(value) = prefs.edit().putString(KEY_BATTLE_SYSTEM_PROMPT, value).apply()

    /** Temperature used for battles. */
    var temperature: Double
        get() = Double.fromBits(prefs.getLong(KEY_TEMPERATURE, DEFAULT_TEMPERATURE_BITS))
        set(value) = prefs.edit().putLong(KEY_TEMPERATURE, value.toBits()).apply()

    /** Maximum tokens per answer in a battle. */
    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)
        set(value) = prefs.edit().putInt(KEY_MAX_TOKENS, value).apply()

    /** Whether the model names are hidden until the vote is cast. */
    var blindBattle: Boolean
        get() = prefs.getBoolean(KEY_BLIND_BATTLE, true)
        set(value) = prefs.edit().putBoolean(KEY_BLIND_BATTLE, value).apply()

    /** Whether the app warns before a battle that may cost real money. */
    var showCostWarnings: Boolean
        get() = prefs.getBoolean(KEY_COST_WARNINGS, true)
        set(value) = prefs.edit().putBoolean(KEY_COST_WARNINGS, value).apply()

    private companion object {
        const val KEY_BATTLE_SYSTEM_PROMPT = "battle_system_prompt"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_BLIND_BATTLE = "blind_battle"
        const val KEY_COST_WARNINGS = "cost_warnings"

        const val DEFAULT_SYSTEM_PROMPT =
            "You are being evaluated in a head-to-head comparison. Answer the user's " +
                "question directly and well. Be concise, concrete, and complete."
        const val DEFAULT_MAX_TOKENS = 2048
        val DEFAULT_TEMPERATURE_BITS = 0.7.toBits()
    }
}
