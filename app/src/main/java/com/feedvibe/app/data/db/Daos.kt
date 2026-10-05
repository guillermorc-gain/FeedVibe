package com.feedvibe.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val EPISODE_ITEM_SELECT = """
    SELECT e.*,
        COALESCE(s.watched, 0) AS watched,
        COALESCE(s.watchLater, 0) AS watchLater,
        COALESCE(s.favorite, 0) AS favorite,
        COALESCE(s.positionMs, 0) AS positionMs,
        COALESCE(s.watchedAt, 0) AS watchedAt,
        sub.title AS channelTitle,
        sub.imageUrl AS channelImage,
        sub.type AS sourceType
    FROM episodes e
    JOIN subscriptions sub ON sub.id = e.subscriptionId
    LEFT JOIN episode_states s ON s.episodeId = e.id
"""

@Dao
interface SubscriptionDao {
    @Query(
        """
        SELECT sub.*,
            (SELECT COUNT(*) FROM episodes e LEFT JOIN episode_states s ON s.episodeId = e.id
                WHERE e.subscriptionId = sub.id AND COALESCE(s.watched, 0) = 0) AS unwatchedCount,
            (SELECT COUNT(*) FROM episodes e WHERE e.subscriptionId = sub.id) AS totalCount,
            (SELECT MAX(e.publishedAt) FROM episodes e WHERE e.subscriptionId = sub.id) AS latestAt
        FROM subscriptions sub
        ORDER BY sub.title COLLATE NOCASE
        """
    )
    fun observeWithCounts(): Flow<List<SubscriptionWithCount>>

    @Query("SELECT * FROM subscriptions ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun get(id: String): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    fun observe(id: String): Flow<SubscriptionEntity?>

    @Query("SELECT DISTINCT category FROM subscriptions WHERE category IS NOT NULL AND category != '' ORDER BY category")
    fun observeCategories(): Flow<List<String>>

    @Upsert
    suspend fun upsert(sub: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE subscriptions SET lastRefreshed = :time, lastError = :error WHERE id = :id")
    suspend fun setRefreshResult(id: String, time: Long, error: String?)
}

@Dao
interface EpisodeDao {
    @Query("$EPISODE_ITEM_SELECT ORDER BY e.publishedAt DESC LIMIT :limit")
    fun observeAll(limit: Int): Flow<List<EpisodeItem>>

    @Query("$EPISODE_ITEM_SELECT WHERE e.subscriptionId = :subId ORDER BY e.publishedAt DESC")
    fun observeForSubscription(subId: String): Flow<List<EpisodeItem>>

    @Query("$EPISODE_ITEM_SELECT WHERE COALESCE(s.watchLater, 0) = 1 ORDER BY e.publishedAt DESC")
    fun observeWatchLater(): Flow<List<EpisodeItem>>

    @Query("$EPISODE_ITEM_SELECT WHERE COALESCE(s.favorite, 0) = 1 ORDER BY e.publishedAt DESC")
    fun observeFavorites(): Flow<List<EpisodeItem>>

    @Query("$EPISODE_ITEM_SELECT WHERE COALESCE(s.watched, 0) = 1 ORDER BY s.watchedAt DESC LIMIT 300")
    fun observeHistory(): Flow<List<EpisodeItem>>

    @Query("$EPISODE_ITEM_SELECT WHERE COALESCE(s.positionMs, 0) > 0 AND COALESCE(s.watched, 0) = 0 ORDER BY s.updatedAt DESC")
    fun observeInProgress(): Flow<List<EpisodeItem>>

    @Query("$EPISODE_ITEM_SELECT WHERE e.id = :id")
    suspend fun getItem(id: String): EpisodeItem?

    @Query("$EPISODE_ITEM_SELECT WHERE e.id = :id")
    fun observeItem(id: String): Flow<EpisodeItem?>

    @Query("SELECT id FROM episodes WHERE subscriptionId = :subId")
    suspend fun idsForSubscription(subId: String): List<String>

    @Query("SELECT * FROM episodes WHERE subscriptionId = :subId")
    suspend fun forSubscription(subId: String): List<EpisodeEntity>

    @Query("SELECT COUNT(*) FROM episodes e LEFT JOIN episode_states s ON s.episodeId = e.id WHERE COALESCE(s.watched, 0) = 0")
    fun observeUnwatchedCount(): Flow<Int>

    @Upsert
    suspend fun upsertAll(episodes: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE subscriptionId = :subId")
    suspend fun deleteForSubscription(subId: String)
}

@Dao
abstract class EpisodeStateDao {
    @Query("SELECT * FROM episode_states WHERE episodeId = :id")
    abstract suspend fun get(id: String): EpisodeStateEntity?

    @Query("SELECT * FROM episode_states WHERE episodeId IN (:ids)")
    abstract suspend fun getMany(ids: List<String>): List<EpisodeStateEntity>

    @Query("SELECT * FROM episode_states")
    abstract suspend fun getAll(): List<EpisodeStateEntity>

    @Query("SELECT COUNT(*) FROM episode_states WHERE watched = 1")
    abstract fun observeWatchedCount(): Flow<Int>

    @Upsert
    abstract suspend fun upsert(state: EpisodeStateEntity)

    @Upsert
    abstract suspend fun upsertAll(states: List<EpisodeStateEntity>)

    @Transaction
    open suspend fun upsertIfNewer(remote: List<EpisodeStateEntity>): List<EpisodeStateEntity> {
        if (remote.isEmpty()) return emptyList()
        val local = remote.chunked(500).flatMap { chunk -> getMany(chunk.map { it.episodeId }) }
            .associateBy { it.episodeId }
        val toApply = remote.filter { r -> (local[r.episodeId]?.updatedAt ?: -1) < r.updatedAt }
        upsertAll(toApply)
        return toApply
    }
}
