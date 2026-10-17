package ai.retrobot.data

import ai.retrobot.core.arena.BattleParticipant
import ai.retrobot.core.arena.Elo
import ai.retrobot.core.providers.ChatProvider
import ai.retrobot.core.providers.ProviderCatalog
import ai.retrobot.core.providers.ProviderFactory
import ai.retrobot.core.providers.ProviderSpec
import ai.retrobot.core.security.KeyVault
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * The single place the rest of the app talks to for accounts, models, votes
 * and ratings.
 *
 * The vote log is the source of truth. The ratings table is a cache of the
 * Elo computation over that log, which means the leaderboard can be rebuilt
 * from scratch at any time and a change to the rating maths does not need a
 * data migration.
 */
class ArenaRepository(
    private val accountDao: ProviderAccountDao,
    private val modelDao: ModelDao,
    private val voteDao: VoteDao,
    private val ratingDao: RatingDao,
    private val keyVault: KeyVault,
    private val providerFactory: ProviderFactory,
) {

    // -----------------------------------------------------------------------
    // Accounts
    // -----------------------------------------------------------------------

    fun observeAccounts(): Flow<List<ProviderAccountEntity>> = accountDao.observeAll()

    suspend fun account(id: String): ProviderAccountEntity? = accountDao.byId(id)

    /**
     * Add a provider account and encrypt its key.
     *
     * @return the id of the new account.
     */
    suspend fun addAccount(
        spec: ProviderSpec,
        label: String,
        baseUrl: String,
        apiKey: String,
    ): String {
        val id = UUID.randomUUID().toString()
        val effectiveLabel = label.ifBlank {
            spec.displayName
        }
        val effectiveUrl = baseUrl.ifBlank { spec.defaultBaseUrl }

        accountDao.upsert(
            ProviderAccountEntity(
                id = id,
                providerId = spec.id,
                label = effectiveLabel,
                baseUrl = effectiveUrl,
                encryptedApiKey = keyVault.encrypt(apiKey.trim()),
            )
        )
        return id
    }

    suspend fun updateAccount(
        id: String,
        label: String,
        baseUrl: String,
        apiKey: String,
    ) {
        val existing = accountDao.byId(id) ?: return
        accountDao.upsert(
            existing.copy(
                label = label.ifBlank { existing.label },
                baseUrl = baseUrl.ifBlank { existing.baseUrl },
                // An empty key means "keep the one already stored", so the
                // user does not have to retype it to change the label.
                encryptedApiKey = if (apiKey.isBlank()) {
                    existing.encryptedApiKey
                } else {
                    keyVault.encrypt(apiKey.trim())
                },
            )
        )
    }

    suspend fun deleteAccount(id: String) {
        modelDao.deleteByAccount(id)
        accountDao.delete(id)
    }

    /** The plaintext API key of an account. */
    suspend fun apiKeyOf(accountId: String): String {
        val account = accountDao.byId(accountId) ?: return ""
        return keyVault.decrypt(account.encryptedApiKey)
    }

    // -----------------------------------------------------------------------
    // Models
    // -----------------------------------------------------------------------

    fun observeModels(): Flow<List<ModelEntity>> = modelDao.observeEnabled()

    suspend fun models(): List<ModelEntity> = modelDao.enabled()

    /**
     * Ask the provider which models it offers and store them.
     *
     * @return the number of models that are now available.
     */
    suspend fun syncModels(accountId: String): Result<Int> = runCatching {
        val account = accountDao.byId(accountId)
            ?: error("That provider account no longer exists.")
        val spec = ProviderCatalog.byId(account.providerId)
            ?: error("Unknown provider: ${account.providerId}")

        val provider = providerFactory.create(
            spec = spec,
            baseUrl = account.baseUrl,
            apiKey = keyVault.decrypt(account.encryptedApiKey),
        )

        val remote = provider.listModels()
        val now = System.currentTimeMillis()
        val entities = remote.map { model ->
            ModelEntity(
                key = modelKey(accountId, model.id),
                accountId = accountId,
                providerId = account.providerId,
                modelId = model.id,
                displayName = model.displayName,
                contextWindow = model.contextWindow,
                inputPricePerMTok = model.inputPricePerMTok,
                outputPricePerMTok = model.outputPricePerMTok,
                addedAt = now,
            )
        }
        modelDao.upsertAll(entities)
        if (entities.isEmpty()) {
            // An empty "NOT IN ()" is a syntax error in SQLite, so an empty
            // catalogue has to be handled by deleting outright.
            modelDao.deleteByAccount(accountId)
        } else {
            modelDao.prune(accountId, entities.map { it.modelId })
        }
        entities.size
    }

    suspend fun setModelEnabled(key: String, enabled: Boolean) {
        val model = modelDao.byKey(key) ?: return
        modelDao.upsertAll(listOf(model.copy(enabled = enabled)))
    }

    /** Build the provider that can serve a given account. */
    suspend fun providerFor(accountId: String): ChatProvider? {
        val account = accountDao.byId(accountId) ?: return null
        val spec = ProviderCatalog.byId(account.providerId) ?: return null
        return providerFactory.create(
            spec = spec,
            baseUrl = account.baseUrl,
            apiKey = keyVault.decrypt(account.encryptedApiKey),
        )
    }

    /** Turn a stored model into something the battle engine can use. */
    fun participantFor(model: ModelEntity): BattleParticipant = BattleParticipant(
        key = model.key,
        displayName = model.displayName,
        providerId = model.providerId,
        modelId = model.modelId,
        accountId = model.accountId,
    )

    // -----------------------------------------------------------------------
    // Votes and ratings
    // -----------------------------------------------------------------------

    fun observeRatings(): Flow<List<RatingEntity>> = ratingDao.observeAll()

    fun observeRecentVotes(limit: Int = 50): Flow<List<VoteEntity>> = voteDao.observeRecent(limit)

    suspend fun voteCount(): Int = voteDao.count()

    /**
     * Record a vote and update the cached ratings.
     */
    suspend fun recordVote(vote: VoteEntity) {
        voteDao.insert(vote)

        val left = ratingDao.byKey(vote.leftModelKey) ?: RatingEntity(vote.leftModelKey)
        val right = ratingDao.byKey(vote.rightModelKey) ?: RatingEntity(vote.rightModelKey)

        val scoreLeft = when (vote.winner) {
            WINNER_LEFT -> 1.0
            WINNER_RIGHT -> 0.0
            else -> 0.5
        }

        val k = Elo.kFactor(maxOf(left.games, right.games))
        val (newLeft, newRight) = Elo.update(left.rating, right.rating, scoreLeft, k)

        ratingDao.upsertAll(
            listOf(
                left.copy(
                    rating = newLeft,
                    games = left.games + 1,
                    wins = left.wins + if (vote.winner == WINNER_LEFT) 1 else 0,
                    losses = left.losses + if (vote.winner == WINNER_RIGHT) 1 else 0,
                    ties = left.ties + if (vote.winner == WINNER_TIE) 1 else 0,
                    updatedAt = System.currentTimeMillis(),
                ),
                right.copy(
                    rating = newRight,
                    games = right.games + 1,
                    wins = right.wins + if (vote.winner == WINNER_RIGHT) 1 else 0,
                    losses = right.losses + if (vote.winner == WINNER_LEFT) 1 else 0,
                    ties = right.ties + if (vote.winner == WINNER_TIE) 1 else 0,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        )
    }

    /**
     * Rebuild every rating from the vote log.
     *
     * Useful after changing the rating maths, and as a self-check that the
     * cache and the log agree.
     */
    suspend fun recomputeRatings() {
        val votes = voteDao.all().sortedBy { it.createdAt }
        val ratings = linkedMapOf<String, RatingEntity>()

        for (vote in votes) {
            val left = ratings[vote.leftModelKey] ?: RatingEntity(vote.leftModelKey)
            val right = ratings[vote.rightModelKey] ?: RatingEntity(vote.rightModelKey)

            val scoreLeft = when (vote.winner) {
                WINNER_LEFT -> 1.0
                WINNER_RIGHT -> 0.0
                else -> 0.5
            }

            val k = Elo.kFactor(maxOf(left.games, right.games))
            val (newLeft, newRight) = Elo.update(left.rating, right.rating, scoreLeft, k)

            ratings[vote.leftModelKey] = left.copy(
                rating = newLeft,
                games = left.games + 1,
                wins = left.wins + if (vote.winner == WINNER_LEFT) 1 else 0,
                losses = left.losses + if (vote.winner == WINNER_RIGHT) 1 else 0,
                ties = left.ties + if (vote.winner == WINNER_TIE) 1 else 0,
                updatedAt = System.currentTimeMillis(),
            )
            ratings[vote.rightModelKey] = right.copy(
                rating = newRight,
                games = right.games + 1,
                wins = right.wins + if (vote.winner == WINNER_RIGHT) 1 else 0,
                losses = right.losses + if (vote.winner == WINNER_LEFT) 1 else 0,
                ties = right.ties + if (vote.winner == WINNER_TIE) 1 else 0,
                updatedAt = System.currentTimeMillis(),
            )
        }

        ratingDao.clear()
        ratingDao.upsertAll(ratings.values.toList())
    }

    companion object {
        const val WINNER_LEFT = "left"
        const val WINNER_RIGHT = "right"
        const val WINNER_TIE = "tie"

        /** The identity of a model inside one account. */
        fun modelKey(accountId: String, modelId: String): String = "$accountId::$modelId"
    }
}
