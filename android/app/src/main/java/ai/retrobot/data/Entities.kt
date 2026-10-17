package ai.retrobot.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A provider account: one set of credentials for one provider.
 *
 * The user can add the same provider more than once — two OpenRouter
 * accounts with different budgets, say — so [id] is generated locally and
 * is not the provider id.
 */
@Entity(tableName = "provider_accounts")
data class ProviderAccountEntity(
    @PrimaryKey val id: String,
    /** The provider this account belongs to, see ProviderCatalog. */
    val providerId: String,
    /** Name the user gave the account. */
    val label: String,
    /** Base URL, e.g. https://openrouter.ai/api/v1 */
    val baseUrl: String,
    /** The API key, encrypted by KeyVault. Never leaves the device. */
    val encryptedApiKey: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A model the user has added to the arena.
 *
 * [key] combines the account and the provider's own model id, so two
 * accounts offering the same model stay distinct.
 */
@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val key: String,
    val accountId: String,
    val providerId: String,
    /** The id the provider uses. */
    val modelId: String,
    val displayName: String,
    val contextWindow: Int? = null,
    val inputPricePerMTok: Double? = null,
    val outputPricePerMTok: Double? = null,
    val enabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
)

/**
 * One cast vote.
 *
 * The vote log is the source of truth; the ratings table is a cache of the
 * Elo computation over that log, so the leaderboard can be recomputed from
 * scratch at any time.
 */
@Entity(tableName = "votes")
data class VoteEntity(
    @PrimaryKey val id: String,
    val prompt: String,
    /** The model actually shown on the left, whichever label it wore. */
    val leftModelKey: String,
    /** The model actually shown on the right. */
    val rightModelKey: String,
    /** "left", "right" or "tie". */
    val winner: String,
    val leftResponse: String,
    val rightResponse: String,
    val leftCostUsd: Double? = null,
    val rightCostUsd: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * The cached Elo standing of one model.
 */
@Entity(tableName = "ratings")
data class RatingEntity(
    @PrimaryKey val modelKey: String,
    val rating: Double = 1000.0,
    val games: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val ties: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)
