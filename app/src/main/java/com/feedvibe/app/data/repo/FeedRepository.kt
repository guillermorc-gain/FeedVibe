package com.feedvibe.app.data.repo

import com.feedvibe.app.data.db.AppDatabase
import com.feedvibe.app.data.db.EpisodeEntity
import com.feedvibe.app.data.db.EpisodeItem
import com.feedvibe.app.data.db.EpisodeStateEntity
import com.feedvibe.app.data.db.SubscriptionEntity
import com.feedvibe.app.data.prefs.SettingsRepository
import com.feedvibe.app.data.sources.ParsedFeed
import com.feedvibe.app.data.sources.SourceResolver
import com.feedvibe.app.data.sources.SourceType
import com.feedvibe.app.data.sync.CloudSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

data class NewEpisodes(val subscription: SubscriptionEntity, val episodes: List<EpisodeEntity>)

data class RefreshResult(val newEpisodes: List<NewEpisodes>, val errors: Int)

class FeedRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val cloud: CloudSync,
) {
    private val refreshMutex = Mutex()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    // ---------- Lectura ----------
    val subscriptionsWithCounts = db.subscriptions().observeWithCounts()
    val categories = db.subscriptions().observeCategories()
    val allEpisodes = db.episodes().observeAll(1500)
    fun episodesFor(subId: String) = db.episodes().observeForSubscription(subId)
    fun subscription(subId: String) = db.subscriptions().observe(subId)
    val watchLater = db.episodes().observeWatchLater()
    val favorites = db.episodes().observeFavorites()
    val history = db.episodes().observeHistory()
    val inProgress = db.episodes().observeInProgress()
    val unwatchedCount = db.episodes().observeUnwatchedCount()
    val watchedCount = db.states().observeWatchedCount()
    fun episode(id: String) = db.episodes().observeItem(id)
    suspend fun getEpisode(id: String) = db.episodes().getItem(id)

    // ---------- Suscripciones ----------

    data class Preview(val type: SourceType, val key: String, val feed: ParsedFeed, val alreadySubscribed: Boolean)

    suspend fun preview(input: String): Preview = withContext(Dispatchers.IO) {
        val r = SourceResolver.resolve(input)
        val feed = SourceResolver.fetch(r.type, r.key, settings.current().hideShorts)
        val id = Ids.subscription(feed.type, r.key)
        Preview(feed.type, r.key, feed, db.subscriptions().get(id) != null)
    }

    suspend fun subscribe(preview: Preview, markExistingWatched: Boolean, category: String?): SubscriptionEntity =
        withContext(Dispatchers.IO) {
            val feed = preview.feed
            val now = System.currentTimeMillis()
            val sub = SubscriptionEntity(
                id = Ids.subscription(feed.type, preview.key),
                type = feed.type,
                sourceKey = preview.key,
                title = feed.title,
                description = feed.description,
                imageUrl = feed.imageUrl,
                siteUrl = feed.siteUrl,
                category = category?.takeIf { it.isNotBlank() },
                addedAt = now,
                lastRefreshed = now,
                updatedAt = now,
            )
            db.subscriptions().upsert(sub)
            val episodes = toEntities(sub, feed)
            db.episodes().upsertAll(episodes)
            cloud.pushSubscription(sub)
            if (markExistingWatched) setWatched(episodes.map { it.id }, true)
            sub
        }

    suspend fun updateSubscription(sub: SubscriptionEntity) {
        val updated = sub.copy(updatedAt = System.currentTimeMillis())
        db.subscriptions().upsert(updated)
        cloud.pushSubscription(updated)
    }

    suspend fun unsubscribe(subId: String) {
        db.episodes().deleteForSubscription(subId)
        db.subscriptions().delete(subId)
        cloud.pushSubscriptionDeleted(subId, System.currentTimeMillis())
    }

    /** Usado al restaurar copias de seguridad / importar OPML. */
    suspend fun importSubscription(type: SourceType, key: String, title: String, imageUrl: String?, siteUrl: String?, category: String?, notify: Boolean) {
        val id = Ids.subscription(type, key)
        if (db.subscriptions().get(id) != null) return
        val sub = SubscriptionEntity(
            id = id, type = type, sourceKey = key, title = title, imageUrl = imageUrl,
            siteUrl = siteUrl, category = category, notify = notify,
        )
        db.subscriptions().upsert(sub)
        cloud.pushSubscription(sub)
    }

    // ---------- Actualización de feeds ----------

    private fun toEntities(sub: SubscriptionEntity, feed: ParsedFeed): List<EpisodeEntity> =
        feed.episodes.map { e ->
            EpisodeEntity(
                id = Ids.episode(sub.id, e.guid),
                subscriptionId = sub.id,
                title = e.title,
                description = e.description,
                url = e.url,
                mediaUrl = e.mediaUrl,
                mediaType = e.mediaType,
                thumbnailUrl = e.thumbnailUrl,
                publishedAt = e.publishedAt,
                durationSec = e.durationSec,
                isLive = e.isLive,
            )
        }

    private suspend fun refreshSubscription(sub: SubscriptionEntity, hideShorts: Boolean): NewEpisodes? {
        return try {
            // La información del canal (foto) solo se pide si aún no la tenemos.
            val feed = SourceResolver.fetch(sub.type, sub.sourceKey, hideShorts, full = sub.imageUrl == null)
            val existing = db.episodes().idsForSubscription(sub.id).toHashSet()
            val entities = toEntities(sub, feed)
            // Los episodios que desaparecen del feed (YouTube solo da los 15 últimos) se conservan.
            db.episodes().upsertAll(entities)
            val firstRefresh = sub.lastRefreshed == 0L
            val newOnes = entities.filter { it.id !in existing }
            val updatedSub = sub.copy(
                title = sub.title.ifBlank { feed.title },
                imageUrl = sub.imageUrl ?: feed.imageUrl,
                description = feed.description.ifBlank { sub.description },
                siteUrl = sub.siteUrl ?: feed.siteUrl,
                lastRefreshed = System.currentTimeMillis(),
                lastError = null,
            )
            db.subscriptions().upsert(updatedSub)
            if (firstRefresh || newOnes.isEmpty()) null else NewEpisodes(updatedSub, newOnes)
        } catch (e: Exception) {
            db.subscriptions().setRefreshResult(sub.id, sub.lastRefreshed, e.message ?: "Error")
            throw e
        }
    }

    suspend fun refreshAll(onlyIds: Set<String>? = null): RefreshResult = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            _refreshing.value = true
            try {
                val hideShorts = settings.current().hideShorts
                val subs = db.subscriptions().getAll().filter { onlyIds == null || it.id in onlyIds }
                val limiter = Semaphore(6)
                val errors = AtomicInteger(0)
                val results = coroutineScope {
                    subs.map { sub ->
                        async {
                            limiter.withPermit {
                                runCatching { refreshSubscription(sub, hideShorts) }
                                    .onFailure { errors.incrementAndGet() }
                                    .getOrNull()
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                if (onlyIds == null) settings.setLastRefresh(System.currentTimeMillis())
                // Filtrar episodios ya vistos (p. ej. marcados en otro dispositivo).
                val filtered = results.mapNotNull { n ->
                    val states = db.states().getMany(n.episodes.map { it.id }).associateBy { it.episodeId }
                    val unseen = n.episodes.filter { states[it.id]?.watched != true }
                    if (unseen.isEmpty()) null else n.copy(episodes = unseen)
                }
                RefreshResult(filtered, errors.get())
            } finally {
                _refreshing.value = false
            }
        }
    }

    suspend fun refreshOne(subId: String) = refreshAll(setOf(subId))

    // ---------- Estado de episodios ----------

    private suspend fun updateStates(ids: List<String>, change: (EpisodeStateEntity) -> EpisodeStateEntity) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = ids.chunked(500).flatMap { db.states().getMany(it) }.associateBy { it.episodeId }
        val subIds = ids.mapNotNull { id -> existing[id]?.subscriptionId?.let { id to it } }.toMap().toMutableMap()
        ids.filter { it !in subIds }.forEach { id -> db.episodes().getItem(id)?.let { subIds[id] = it.episode.subscriptionId } }
        val updated = ids.map { id ->
            val base = existing[id] ?: EpisodeStateEntity(episodeId = id, subscriptionId = subIds[id], updatedAt = 0)
            change(base).copy(updatedAt = now, subscriptionId = base.subscriptionId ?: subIds[id])
        }
        db.states().upsertAll(updated)
        cloud.pushStates(updated)
    }

    suspend fun setWatched(ids: List<String>, watched: Boolean) = updateStates(ids) {
        it.copy(
            watched = watched,
            watchedAt = if (watched) System.currentTimeMillis() else 0,
            positionMs = if (watched) 0 else it.positionMs,
            watchLater = if (watched) false else it.watchLater,
        )
    }

    suspend fun toggleWatched(item: EpisodeItem) = setWatched(listOf(item.episode.id), !item.watched)

    suspend fun toggleWatchLater(item: EpisodeItem) = updateStates(listOf(item.episode.id)) { it.copy(watchLater = !item.watchLater) }

    suspend fun toggleFavorite(item: EpisodeItem) = updateStates(listOf(item.episode.id)) { it.copy(favorite = !item.favorite) }

    suspend fun savePosition(id: String, positionMs: Long) = updateStates(listOf(id)) { it.copy(positionMs = positionMs) }

    /** Marca como vistos este episodio y todos los anteriores del mismo canal. */
    suspend fun markOlderWatched(item: EpisodeItem) {
        val ids = db.episodes().forSubscription(item.episode.subscriptionId)
            .filter { it.publishedAt <= item.episode.publishedAt }.map { it.id }
        setWatched(ids, true)
    }

    suspend fun markAllWatched(subId: String?) {
        val ids = if (subId != null) db.episodes().idsForSubscription(subId)
        else db.subscriptions().getAll().flatMap { db.episodes().idsForSubscription(it.id) }
        val states = ids.chunked(500).flatMap { db.states().getMany(it) }.associateBy { it.episodeId }
        setWatched(ids.filter { states[it]?.watched != true }, true)
    }

    suspend fun markListWatched(items: List<EpisodeItem>, watched: Boolean) =
        setWatched(items.filter { it.watched != watched }.map { it.episode.id }, watched)

    /** Para copias de seguridad. */
    suspend fun allStates() = db.states().getAll()
    suspend fun allSubscriptions() = db.subscriptions().getAll()
    suspend fun restoreStates(states: List<EpisodeStateEntity>) {
        val applied = db.states().upsertIfNewer(states)
        cloud.pushStates(applied)
    }
}
