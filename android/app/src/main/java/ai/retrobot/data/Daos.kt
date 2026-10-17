package ai.retrobot.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderAccountDao {

    @Query("SELECT * FROM provider_accounts ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ProviderAccountEntity>>

    @Query("SELECT * FROM provider_accounts WHERE id = :id")
    suspend fun byId(id: String): ProviderAccountEntity?

    @Query("SELECT * FROM provider_accounts WHERE providerId = :providerId LIMIT 1")
    suspend fun firstForProvider(providerId: String): ProviderAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: ProviderAccountEntity)

    @Query("DELETE FROM provider_accounts WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ModelDao {

    @Query("SELECT * FROM models WHERE enabled = 1 ORDER BY displayName ASC")
    fun observeEnabled(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE enabled = 1 ORDER BY displayName ASC")
    suspend fun enabled(): List<ModelEntity>

    @Query("SELECT * FROM models WHERE accountId = :accountId")
    suspend fun forAccount(accountId: String): List<ModelEntity>

    @Query("SELECT * FROM models WHERE `key` = :key")
    suspend fun byKey(key: String): ModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(models: List<ModelEntity>)

    @Query("DELETE FROM models WHERE accountId = :accountId AND modelId NOT IN (:keep)")
    suspend fun prune(accountId: String, keep: List<String>)

    @Query("DELETE FROM models WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: String)
}

@Dao
interface VoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vote: VoteEntity)

    @Query("SELECT * FROM votes ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<VoteEntity>>

    @Query("SELECT * FROM votes ORDER BY createdAt ASC")
    suspend fun all(): List<VoteEntity>

    @Query("SELECT COUNT(*) FROM votes")
    suspend fun count(): Int
}

@Dao
interface RatingDao {

    @Query("SELECT * FROM ratings ORDER BY rating DESC")
    fun observeAll(): Flow<List<RatingEntity>>

    @Query("SELECT * FROM ratings")
    suspend fun all(): List<RatingEntity>

    @Query("SELECT * FROM ratings WHERE modelKey = :modelKey")
    suspend fun byKey(modelKey: String): RatingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rating: RatingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(ratings: List<RatingEntity>)

    @Query("DELETE FROM ratings")
    suspend fun clear()
}
