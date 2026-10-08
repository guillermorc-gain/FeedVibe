package com.feedvibe.app.data.repo

import com.feedvibe.app.data.db.AppDatabase
import com.feedvibe.app.data.db.EpisodeEntity
import com.feedvibe.app.data.db.EpisodeItem
import com.feedvibe.app.data.db.EpisodeStateEntity
import com.feedvibe.app.data.db.SubscriptionEntity
import com.feedvibe.app.data.prefs.SettingsRepository
import com.feedvibe.app.BuildConfig
import com.feedvibe.app.data.sources.ParsedEpisode
import com.feedvibe.app.data.sources.ParsedFeed
import com.feedvibe.app.data.sources.SourceException
import com.feedvibe.app.data.sources.OfflineException
import com.feedvibe.app.data.sources.YouTubeApi
import com.feedvibe.app.data.sources.YouTubePage
import com.feedvibe.app.data.sources.SourceResolver
import com.feedvibe.app.data.sources.SourceType
import com.feedvibe.app.data.sync.CloudSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

data class NewEpisodes(val subscription: SubscriptionEntity, val episodes: List<EpisodeEntity>)

/** [topError]: el motivo de error más repetido (para entender qué pasa cuando fallan muchos). */
data class RefreshResult(val newEpisodes: List<NewEpisodes>, val errors: Int, val topError: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class FeedRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val cloud: CloudSync,
) {
    private val refreshMutex = Mutex()

    /**
     * Cargas automáticas del historial completo (canales que llegan de otro dispositivo o de una
     * copia): pueden ser miles de vídeos por canal, así que van de una en una y fuera de la
     * actualización, para no bloquearla (antes la dejaban horas sin terminar) ni agotar la memoria.
     */
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val historyQueueMutex = Mutex()
    private val queuedHistory = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    companion object {
        /** Episodios vistos que se guardan en el Historial de la Biblioteca. */
        const val HISTORY_KEEP = 200
    }

    fun queueFullHistory(subId: String) {
        if (!queuedHistory.add(subId)) return
        historyScope.launch {
            try {
                historyQueueMutex.withLock {
                    runCatching { loadFullHistory(subId, markOldWatched = false, auto = true) }
                }
            } finally {
                queuedHistory.remove(subId)
            }
        }
    }
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Canales cargando su historial completo -> vídeos cargados hasta ahora. */
    private val _historyProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val historyProgress: StateFlow<Map<String, Int>> = _historyProgress.asStateFlow()

    // ---------- Lectura ----------
    // Todas las listas reaccionan al ajuste «Ocultar Shorts».
    private val hideShorts = settings.settings.map { it.hideShorts }.distinctUntilChanged()

    val subscriptionsWithCounts = hideShorts.flatMapLatest { db.subscriptions().observeWithCounts(it) }
    val categories = db.subscriptions().observeCategories()

    /** Novedades: según «ocultar vistos» y el orden elegido. */
    val feedEpisodes: Flow<List<EpisodeItem>> = settings.settings
        .map { Triple(it.hideShorts, it.hideWatched, it.feedOldestFirst) }
        .distinctUntilChanged()
        .flatMapLatest { (shorts, onlyUnwatched, oldestFirst) ->
            val dao = db.episodes()
            when {
                onlyUnwatched && oldestFirst -> dao.observeUnwatchedAsc(shorts, 3000)
                onlyUnwatched -> dao.observeUnwatchedDesc(shorts, 3000)
                oldestFirst -> dao.observeAllAsc(shorts, 3000)
                else -> dao.observeAllDesc(shorts, 3000)
            }
        }

    fun episodesFor(subId: String) = hideShorts.flatMapLatest { db.episodes().observeForSubscription(subId, it) }
    fun subscription(subId: String) = db.subscriptions().observe(subId)
    val watchLater = hideShorts.flatMapLatest { db.episodes().observeWatchLater(it) }
    val favorites = hideShorts.flatMapLatest { db.episodes().observeFavorites(it) }
    val history = hideShorts.flatMapLatest { db.episodes().observeHistory(it) }
    val inProgress = hideShorts.flatMapLatest { db.episodes().observeInProgress(it) }
    val unwatchedCount = hideShorts.flatMapLatest { db.episodes().observeUnwatchedCount(it) }
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
            saveEpisodes(episodes)
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

    /** Usado al restaurar copias de seguridad / importar OPML. Devuelve el id si es nueva. */
    suspend fun importSubscription(type: SourceType, key: String, title: String, imageUrl: String?, siteUrl: String?, category: String?, notify: Boolean, fullHistory: Boolean): String? {
        val id = Ids.subscription(type, key)
        if (db.subscriptions().get(id) != null) return null
        val sub = SubscriptionEntity(
            id = id, type = type, sourceKey = key, title = title, imageUrl = imageUrl,
            siteUrl = siteUrl, category = category, notify = notify, fullHistory = fullHistory,
        )
        db.subscriptions().upsert(sub)
        cloud.pushSubscription(sub)
        return id
    }

    // ---------- Actualización de feeds ----------

    private fun toEntities(sub: SubscriptionEntity, feed: ParsedFeed): List<EpisodeEntity> =
        toEntities(sub, feed.episodes)

    private fun toEntities(sub: SubscriptionEntity, episodes: List<ParsedEpisode>): List<EpisodeEntity> =
        episodes.map { e ->
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
                isShort = e.isShort || e.url.contains("/shorts/"),
            )
        }

    private suspend fun refreshSubscription(sub: SubscriptionEntity, hideShorts: Boolean, apiKey: String = ""): NewEpisodes? {
        return try {
            // La información del canal (foto) solo se pide si aún no la tenemos.
            val feed = SourceResolver.fetch(sub.type, sub.sourceKey, hideShorts, full = sub.imageUrl == null, youtubeApiKey = apiKey)
            val existing = db.episodes().idsForSubscription(sub.id).toHashSet()
            // Los episodios que desaparecen del feed (YouTube solo da los 15 últimos) se conservan.
            val saved = saveEpisodes(toEntities(sub, feed))
            val firstRefresh = sub.lastRefreshed == 0L
            val newOnes = saved.filter { it.id !in existing }
            val updatedSub = sub.copy(
                title = sub.title.ifBlank { feed.title },
                imageUrl = sub.imageUrl ?: feed.imageUrl,
                description = feed.description.ifBlank { sub.description },
                siteUrl = sub.siteUrl ?: feed.siteUrl,
                lastRefreshed = System.currentTimeMillis(),
                lastError = null,
            )
            db.subscriptions().upsert(updatedSub)
            // Canal con historial completo que este dispositivo aún no tiene (restaurado, otro móvil…).
            // (Si ya hay estados del canal es que se cargó y se limpiaron los vistos.)
            if (sub.fullHistory && existing.isEmpty() && db.states().countForSubscription(sub.id) == 0) queueFullHistory(sub.id)
            if (firstRefresh || newOnes.isEmpty()) null else NewEpisodes(updatedSub, newOnes)
        } catch (e: Exception) {
            // Sin conexión no es un fallo del canal: no se le marca con error.
            if (e !is OfflineException) db.subscriptions().setRefreshResult(sub.id, sub.lastRefreshed, e.message ?: "Error")
            throw e
        }
    }

    suspend fun refreshAll(onlyIds: Set<String>? = null): RefreshResult = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            _refreshing.value = true
            com.feedvibe.app.CrashReport.note("Actualizando ${onlyIds?.size ?: "todos los"} canales")
            try {
                val hideShorts = settings.current().hideShorts
                // Los canales en pausa no se actualizan (salvo que se pida uno concreto).
                val subs = db.subscriptions().getAll().filter { if (onlyIds == null) !it.paused else it.id in onlyIds }
                val apiKey = youtubeApiKey()
                val limiter = Semaphore(6)
                // A YouTube, menos a la vez: muchas peticiones seguidas desde el mismo móvil hacen
                // que empiece a rechazarlas.
                val youtubeLimiter = Semaphore(3)
                val errors = AtomicInteger(0)
                val reasons = java.util.concurrent.ConcurrentHashMap<String, Int>()
                val offline = java.util.concurrent.ConcurrentLinkedQueue<SubscriptionEntity>()
                suspend fun pass(list: List<SubscriptionEntity>, retrying: Boolean): List<NewEpisodes> = coroutineScope {
                    list.map { sub ->
                        async {
                            limiter.withPermit {
                                val run: suspend () -> NewEpisodes? = {
                                    // Con límite de tiempo: un canal que no responde no bloquea al resto.
                                    withTimeout(90_000) { refreshSubscription(sub, hideShorts, apiKey) }
                                }
                                runCatching { if (sub.type == SourceType.YOUTUBE) youtubeLimiter.withPermit { run() } else run() }
                                    .onFailure { e ->
                                        // Sin conexión: se reintenta al final (la red puede tardar en volver).
                                        if (e is OfflineException && !retrying) {
                                            offline.add(sub)
                                            return@onFailure
                                        }
                                        errors.incrementAndGet()
                                        val reason = if (e is TimeoutCancellationException) "No ha respondido a tiempo"
                                        else e.message.orEmpty().replace(Regex(" al abrir \\S+"), "").ifBlank { "Error" }
                                        if (e is TimeoutCancellationException) {
                                            runCatching { db.subscriptions().setRefreshResult(sub.id, sub.lastRefreshed, reason) }
                                        }
                                        reasons.merge(reason.take(120), 1, Int::plus)
                                    }
                                    .getOrNull()
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                val results = pass(subs, retrying = false).toMutableList()
                if (offline.isNotEmpty()) {
                    com.feedvibe.app.CrashReport.note("Sin conexión en ${offline.size} canales: se reintenta")
                    kotlinx.coroutines.delay(5_000)
                    results += pass(offline.toList(), retrying = true)
                }
                if (onlyIds == null && offline.size < subs.size) settings.setLastRefresh(System.currentTimeMillis())
                // Filtrar episodios ya vistos (p. ej. marcados en otro dispositivo).
                val filtered = results.mapNotNull { n ->
                    val states = db.states().getMany(n.episodes.map { it.id }).associateBy { it.episodeId }
                    val unseen = n.episodes.filter { states[it.id]?.watched != true }
                    if (unseen.isEmpty()) null else n.copy(episodes = unseen)
                }
                com.feedvibe.app.CrashReport.note("Actualización terminada (${errors.get()} errores)")
                pruneWatched()
                RefreshResult(filtered, errors.get(), reasons.maxByOrNull { it.value }?.key)
            } finally {
                _refreshing.value = false
            }
        }
    }

    suspend fun refreshOne(subId: String) = refreshAll(setOf(subId))

    // ---------- Historial completo (YouTube Data API) ----------

    /** Clave configurada en la app o, si no hay, la incluida al compilar. */
    suspend fun youtubeApiKey(): String =
        settings.current().youtubeApiKey.trim().ifBlank { BuildConfig.YOUTUBE_API_KEY }

    fun canLoadFullHistory(sub: SubscriptionEntity) = sub.type == SourceType.YOUTUBE

    /**
     * Carga todos los vídeos del canal (no solo los 15 del RSS).
     * @param markOldWatched marca como vistos los vídeos que no se conocían.
     * @param auto carga automática (al abrir la app, desde otro dispositivo): los vídeos más
     *   antiguos que todo lo ya conocido del canal se marcan como vistos.
     * @return número de vídeos nuevos añadidos.
     */
    suspend fun loadFullHistory(subId: String, markOldWatched: Boolean, auto: Boolean = false, onProgress: (Int) -> Unit = {}): Int =
        withContext(Dispatchers.IO) {
            val sub = db.subscriptions().get(subId) ?: throw SourceException("Canal no encontrado")
            if (!canLoadFullHistory(sub)) throw SourceException("Esta plataforma ya muestra todos los episodios disponibles en su feed")
            val apiKey = youtubeApiKey()
            if (subId in _historyProgress.value) return@withContext 0
            _historyProgress.update { it + (subId to 0) }
            com.feedvibe.app.CrashReport.note("Cargando todos los vídeos de ${sub.title}")
            val episodes = try {
                val progress: (Int) -> Unit = { n ->
                    _historyProgress.update { it + (subId to n) }
                    onProgress(n)
                }
                // Con clave: API oficial (fechas exactas y duraciones). Sin clave: la página del canal.
                if (apiKey.isNotBlank()) YouTubeApi.fetchAll(apiKey, sub.sourceKey, settings.current().hideShorts, onProgress = progress)
                else YouTubePage.fetchAll(sub.sourceKey, onProgress = progress)
            } finally {
                _historyProgress.update { it - subId }
            }
            val known = db.episodes().forSubscription(sub.id)
            val existing = known.map { it.id }.toHashSet()
            val oldestKnown = known.minOfOrNull { it.publishedAt }
            val entities = saveEpisodes(toEntities(sub, episodes))
            val newOnes = entities.filter { it.id !in existing }
            val newIds = newOnes.map { it.id }
            val toMark = if (markOldWatched) newOnes else {
                // Los vídeos antiguos que aparecen ahora no salen como nuevos si ya habías marcado
                // como visto algo posterior del canal, ni (en la carga automática) si son anteriores
                // a todo lo que ya conocías del canal.
                val until = watchedUntil(sub.id)
                newOnes.filter {
                    (until != null && it.publishedAt <= until) ||
                        (auto && oldestKnown != null && it.publishedAt < oldestKnown)
                }
            }
            markHistoryWatched(sub.id, toMark.map { it.id })
            com.feedvibe.app.CrashReport.note("${sub.title}: ${entities.size} vídeos, ${newIds.size} nuevos, ${toMark.size} marcados")
            pruneWatched()
            if (!sub.fullHistory) {
                // Se sincroniza: el resto de dispositivos cargarán también el historial.
                val updated = (db.subscriptions().get(sub.id) ?: sub).copy(fullHistory = true, updatedAt = System.currentTimeMillis())
                db.subscriptions().upsert(updated)
                cloud.pushSubscription(updated)
            }
            newIds.size
        }

    /**
     * Guarda episodios sin perder la duración que ya se conocía si el feed no la trae (el RSS de
     * YouTube no la da). Los ya vistos que se limpiaron ([pruneWatched]) no se vuelven a guardar.
     * @return los episodios guardados.
     */
    private suspend fun saveEpisodes(episodes: List<EpisodeEntity>): List<EpisodeEntity> {
        val ids = episodes.map { it.id }
        val present = ids.chunked(500).flatMap { db.episodes().existingIds(it) }.toHashSet()
        val seen = ids.filter { it !in present }.chunked(500).flatMap { db.states().getMany(it) }
            .filter { it.watched && !it.favorite && !it.watchLater }.mapTo(HashSet()) { it.episodeId }
        val keep = episodes.filter { it.id !in seen }
        val missing = keep.filter { it.durationSec <= 0 }.map { it.id }
        val known = missing.chunked(500).flatMap { db.episodes().knownDurations(it) }.associate { it.id to it.durationSec }
        db.episodes().upsertAll(keep.map { e -> known[e.id]?.let { e.copy(durationSec = it) } ?: e })
        return keep
    }

    /**
     * Para no guardar información inútil: los episodios vistos que no están en la Biblioteca se
     * borran (se conserva solo su estado, que es pequeño y es lo que se sincroniza).
     */
    suspend fun pruneWatched() = withContext(Dispatchers.IO) {
        runCatching { db.episodes().pruneWatched(HISTORY_KEEP) }
            .onSuccess { if (it > 0) com.feedvibe.app.CrashReport.note("Limpiados $it episodios vistos") }
    }

    /** El reproductor averigua la duración de los vídeos que no la traían. */
    suspend fun setDurationIfUnknown(id: String, sec: Long) {
        if (sec > 0) db.episodes().setDurationIfUnknown(id, sec)
    }

    /** Fecha del episodio más reciente marcado como visto en el canal (null si no hay ninguno). */
    private suspend fun watchedUntil(subId: String): Long? {
        val episodes = db.episodes().forSubscription(subId)
        val watched = episodes.map { it.id }.chunked(500).flatMap { db.states().getMany(it) }
            .filter { it.watched }.map { it.episodeId }.toHashSet()
        return episodes.filter { it.id in watched }.maxOfOrNull { it.publishedAt }
    }

    /**
     * Reparación (una vez): en cada canal, los episodios que nunca has tocado y son anteriores al
     * último que marcaste como visto pasan a vistos. Arregla los vídeos antiguos que una versión
     * anterior añadió como «sin ver» al cargar el historial completo.
     */
    suspend fun repairOldUnwatched() = withContext(Dispatchers.IO) {
        com.feedvibe.app.CrashReport.note("Reparando vistos")
        for (sub in db.subscriptions().getAll()) {
            val episodes = db.episodes().forSubscription(sub.id)
            val states = episodes.map { it.id }.chunked(500).flatMap { db.states().getMany(it) }.associateBy { it.episodeId }
            val until = episodes.filter { states[it.id]?.watched == true }.maxOfOrNull { it.publishedAt } ?: continue
            markHistoryWatched(sub.id, episodes.filter { it.publishedAt <= until && it.id !in states }.map { it.id })
        }
    }

    /**
     * Marca como vistos vídeos antiguos del historial solo en este dispositivo: pueden ser miles y
     * no se suben a la nube (cada dispositivo aplica la misma regla al cargar el historial).
     * Con updatedAt = 1 cualquier cambio real, de aquí o de otro dispositivo, tiene prioridad.
     */
    private suspend fun markHistoryWatched(subId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        val existing = ids.chunked(500).flatMap { db.states().getMany(it) }.associateBy { it.episodeId }
        val states = ids.filter { existing[it]?.watched != true }.map { id ->
            (existing[id] ?: EpisodeStateEntity(episodeId = id, subscriptionId = subId))
                .copy(watched = true, watchedAt = 0, positionMs = 0, watchLater = false, updatedAt = 1)
        }
        states.chunked(500).forEach { db.states().upsertAll(it) }
    }

    // ---------- Estado de episodios ----------

    private suspend fun updateStates(ids: List<String>, change: (EpisodeStateEntity) -> EpisodeStateEntity) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = ids.chunked(500).flatMap { db.states().getMany(it) }.associateBy { it.episodeId }
        val subIds = ids.mapNotNull { id -> existing[id]?.subscriptionId?.let { id to it } }.toMap().toMutableMap()
        ids.filter { it !in subIds }.chunked(500).forEach { chunk ->
            db.episodes().subscriptionIds(chunk).forEach { subIds[it.id] = it.subscriptionId }
        }
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

    // ---------- Acciones sobre varios elementos (modo selección) ----------

    suspend fun setWatchLater(ids: List<String>, value: Boolean) = updateStates(ids) { it.copy(watchLater = value) }

    suspend fun setFavorite(ids: List<String>, value: Boolean) = updateStates(ids) { it.copy(favorite = value) }

    /** Olvida por dónde ibas (posición de reproducción) en estos episodios. */
    suspend fun resetProgress(ids: List<String>) = updateStates(ids) { it.copy(positionMs = 0) }

    suspend fun unsubscribeMany(subIds: Collection<String>) = subIds.forEach { unsubscribe(it) }

    suspend fun setPaused(subIds: Collection<String>, paused: Boolean) = updateSubscriptions(subIds) { it.copy(paused = paused) }

    /** Carga el historial completo de varios canales, uno detrás de otro. */
    suspend fun loadFullHistoryMany(subIds: Collection<String>, markOldWatched: Boolean, auto: Boolean = false) {
        for (id in subIds) runCatching { loadFullHistory(id, markOldWatched, auto) }
    }

    /**
     * Canales de YouTube que aún no tienen el historial completo (p. ej. importados de un OPML)
     * o que se cargaron con una versión anterior (títulos en inglés, Shorts sin marcar).
     */
    suspend fun backfillFullHistory(includeAlreadyLoaded: Boolean) {
        val subs = db.subscriptions().getAll()
            .filter { it.type == SourceType.YOUTUBE && !it.paused && (includeAlreadyLoaded || !it.fullHistory) }
        loadFullHistoryMany(subs.map { it.id }, markOldWatched = false, auto = true)
    }

    suspend fun markSubscriptionsWatched(subIds: Collection<String>) = subIds.forEach { markAllWatched(it) }

    suspend fun updateSubscriptions(subIds: Collection<String>, change: (SubscriptionEntity) -> SubscriptionEntity) {
        subIds.forEach { id -> db.subscriptions().get(id)?.let { updateSubscription(change(it)) } }
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
