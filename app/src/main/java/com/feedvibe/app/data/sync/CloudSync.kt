package com.feedvibe.app.data.sync

import android.util.Log
import com.feedvibe.app.data.db.AppDatabase
import com.feedvibe.app.data.db.EpisodeEntity
import com.feedvibe.app.data.db.EpisodeStateEntity
import com.feedvibe.app.data.db.SubscriptionEntity
import com.feedvibe.app.data.prefs.SettingsRepository
import com.feedvibe.app.data.sources.SourceType
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date

enum class SyncStatus { OFF, CONNECTING, SYNCED, ERROR }

/** Hacia dónde va la información ahora mismo: se sube (UP) o se recibe de otro dispositivo (DOWN). */
enum class SyncDirection { UP, DOWN }

/**
 * Sincronización en tiempo real entre dispositivos con Cloud Firestore.
 *
 * users/{uid}                  -> perfil (apodo, foto)
 * users/{uid}/channels/{subId} -> un documento por canal: la suscripción (con borrado
 *                                 lógico "deleted") y, en "s", el estado de los episodios
 *                                 marcados a mano (visto / ver más tarde / favorito y la hora
 *                                 del cambio, en un número); en "p", la posición de reproducción.
 *
 * Así la primera sincronización cuesta una lectura y una escritura por canal en vez de una
 * por episodio. Los vistos automáticos del historial no se suben: cada dispositivo los calcula.
 * "updatedAt" (hora del dispositivo) resuelve conflictos: gana el cambio más reciente;
 * "serverUpdatedAt" (hora del servidor) sirve para descargar solo lo que ha cambiado.
 */
class CloudSync(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val available: Boolean,
) {
    @Volatile
    private var firestore: FirebaseFirestore? = null
    private val listeners = mutableListOf<ListenerRegistration>()
    private var uid: String? = null

    private val _status = MutableStateFlow(SyncStatus.OFF)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Subidas pendientes de confirmar y cambios recibidos que se están aplicando. */
    private val _uploading = MutableStateFlow(0)
    private val _receiving = MutableStateFlow(0)

    /** Hacia dónde va la información ahora mismo (para la animación del título); null si nada. */
    val direction: Flow<SyncDirection?> = combine(_status, _uploading, _receiving) { st, up, down ->
        when {
            up > 0 -> SyncDirection.UP
            down > 0 || st == SyncStatus.CONNECTING -> SyncDirection.DOWN
            else -> null
        }
    }.distinctUntilChanged()

    /** Se está sincronizando ahora mismo. */
    val syncing: Flow<Boolean> = direction.map { it != null }.distinctUntilChanged()

    /** Aplicando cambios que llegan de otro dispositivo. */
    private suspend fun <T> busy(block: suspend () -> T): T {
        _receiving.update { it + 1 }
        try {
            return block()
        } finally {
            _receiving.update { it - 1 }
        }
    }

    /**
     * Una escritura cuenta como «sincronizando» hasta que el servidor la confirma (como mucho
     * 10 s: sin conexión se queda en cola y no hay que tener la animación todo el rato).
     */
    private fun track(task: com.google.android.gms.tasks.Task<*>) {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish() { if (done.compareAndSet(false, true)) _uploading.update { it - 1 } }
        _uploading.update { it + 1 }
        task.addOnCompleteListener { finish() }
        scope.launch { delay(10_000); finish() }
    }

    /** Explicación del último error, para mostrarla junto al estado. */
    private val _detail = MutableStateFlow<String?>(null)
    val detail: StateFlow<String?> = _detail.asStateFlow()

    /** Llamado cuando llega una suscripción nueva desde otro dispositivo. */
    var onRemoteSubscriptionAdded: ((SubscriptionEntity) -> Unit)? = null
    /** Otro dispositivo cargó el historial completo de un canal: hay que cargarlo aquí también. */
    var onRemoteFullHistory: ((SubscriptionEntity) -> Unit)? = null
    var onRemoteProfile: ((nickname: String?, photoBase64: String?, photoUpdatedAt: Long) -> Unit)? = null

    private fun userDoc(u: String) = (firestore ?: error("Firestore no disponible")).collection("users").document(u)

    /**
     * La app está a la vista. Las escuchas en tiempo real mantienen una conexión abierta
     * (y gastan batería), así que solo se activan mientras se usa la app; en segundo plano
     * los cambios se envían igual y se descargan de forma puntual con [pullOnce].
     */
    private var foreground = false
    /** Ya se ha comprobado la subida inicial de esta cuenta y se puede escuchar. */
    private var ready = false

    @Synchronized
    fun start(newUid: String) {
        if (!available || uid == newUid) return
        stop()
        uid = newUid
        _status.value = SyncStatus.CONNECTING
        _detail.value = null
        scope.launch {
            firestore = FirestoreHolder.get(settings)
            // Primer inicio de sesión de este usuario en este dispositivo: subir lo local.
            if (settings.syncedUid() != newUid) {
                settings.setStateCursor(0)
                // Solo se da por sincronizado cuando la subida inicial termina bien. Si falla
                // (sin conexión, límite diario agotado…) se reintenta cada vez más espaciado.
                var waitMs = 15_000L
                while (true) {
                    if (synchronized(this@CloudSync) { uid != newUid }) return@launch
                    val ok = runCatching { uploadAll(newUid) }
                        .onFailure {
                            Log.w(TAG, "uploadAll", it)
                            _status.value = SyncStatus.ERROR
                            _detail.value = explain(it)
                        }
                        .isSuccess
                    if (ok) break
                    delay(waitMs)
                    waitMs = (waitMs * 2).coerceAtMost(5 * 60_000L)
                }
                settings.setSyncedUid(newUid)
            }
            synchronized(this@CloudSync) {
                if (uid == newUid) {
                    ready = true
                    _status.value = SyncStatus.SYNCED
                    _detail.value = null
                }
            }
            updateListeners()
        }
    }

    private fun explain(e: Throwable): String = when {
        e is TimeoutCancellationException -> "Firebase no responde; se reintentará solo."
        (e as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED ->
            "Se ha agotado el límite diario gratuito de Firebase. Se reanudará solo cuando se renueve (hacia las 9:00)."
        (e as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            "Firebase rechaza el acceso: revisa las reglas de Firestore."
        (e as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.UNAVAILABLE ->
            "Sin conexión con Firebase; se reintentará solo."
        else -> e.message ?: e.javaClass.simpleName
    }

    fun setForeground(value: Boolean) {
        synchronized(this) { foreground = value }
        scope.launch { updateListeners() }
    }

    /** Engancha o suelta las escuchas en tiempo real según haya sesión y la app esté a la vista. */
    private suspend fun updateListeners() {
        val cursor = settings.stateCursor()
        synchronized(this) {
            val u = uid
            if (u != null && ready && foreground) {
                if (listeners.isEmpty()) attachListeners(u, cursor)
            } else if (listeners.isNotEmpty()) {
                listeners.forEach { it.remove() }
                listeners.clear()
            }
        }
    }

    @Synchronized
    fun stop() {
        listeners.forEach { it.remove() }
        listeners.clear()
        uid = null
        ready = false
        _status.value = SyncStatus.OFF
    }

    suspend fun signedOut() {
        stop()
        settings.setSyncedUid(null)
        settings.setStateCursor(0)
    }

    private fun channels(u: String) = userDoc(u).collection("channels")

    private fun sinceCursor(cursor: Long) = Timestamp(Date((cursor - 10 * 60_000).coerceAtLeast(0)))

    private fun attachListeners(u: String, cursor: Long) {
        if (firestore == null) return
        // Solo los canales que han cambiado desde la última vez (margen de 10 minutos por
        // si los relojes no van exactamente igual).
        listeners += channels(u)
            .whereGreaterThan("serverUpdatedAt", sinceCursor(cursor))
            .addSnapshotListener { snap, err ->
                if (err != null) { _status.value = SyncStatus.ERROR; _detail.value = explain(err); Log.w(TAG, "channels", err); return@addSnapshotListener }
                // Los cambios propios aún sin confirmar (hasPendingWrites) ya están aplicados aquí.
                val docs = snap?.documentChanges
                    ?.filter { it.type != DocumentChange.Type.REMOVED && !it.document.metadata.hasPendingWrites() }
                    ?.map { it.document } ?: return@addSnapshotListener
                if (docs.isNotEmpty()) scope.launch { runCatching { busy { applyRemoteChannels(docs) } }.onFailure { Log.w(TAG, "apply", it) } }
                _status.value = SyncStatus.SYNCED
                _detail.value = null
            }
        listeners += userDoc(u).addSnapshotListener { snap, _ ->
            if (snap == null || !snap.exists()) return@addSnapshotListener
            onRemoteProfile?.invoke(
                snap.getString("nickname"),
                snap.getString("photo"),
                snap.getLong("photoUpdatedAt") ?: 0,
            )
        }
    }

    /** Descarga puntual de cambios (lo usa el trabajo en segundo plano antes de notificar). */
    suspend fun pullOnce() {
        val u = uid ?: settings.syncedUid() ?: return
        if (!available) return
        firestore = FirestoreHolder.get(settings)
        runCatching {
            val docs = channels(u).whereGreaterThan("serverUpdatedAt", sinceCursor(settings.stateCursor())).get().await()
            busy { applyRemoteChannels(docs.documents) }
        }.onFailure { Log.w(TAG, "pullOnce", it) }
    }

    /** Lo que hay en este dispositivo y falta (o es más antiguo) en la nube. */
    private class Pending {
        val subs = mutableMapOf<String, SubscriptionEntity>()
        val states = mutableListOf<EpisodeStateEntity>()
    }

    /**
     * Aplica los documentos de canal que vienen de la nube. Gana siempre el cambio más
     * reciente de cada canal y de cada episodio. Con [pending] se apunta además lo local que
     * es más nuevo que lo remoto, para subirlo (solo en la primera sincronización).
     */
    private suspend fun applyRemoteChannels(docs: List<DocumentSnapshot>, pending: Pending? = null) {
        if (docs.isEmpty()) return
        var maxServer = settings.stateCursor()
        // Primera sincronización: hacen falta todos los estados locales para saber qué subir;
        // después basta con los de los episodios que llegan.
        val localStates = if (pending != null) db.states().getAll().associateBy { it.episodeId }
        else docs.flatMap { stateMapOf(it).keys }.chunked(500).flatMap { db.states().getMany(it) }.associateBy { it.episodeId }
        val toApply = mutableListOf<EpisodeStateEntity>()
        val seen = HashSet<String>()
        for (d in docs) {
            d.getTimestamp("serverUpdatedAt")?.toDate()?.time?.let { if (it > maxServer) maxServer = it }
            applyRemoteMeta(d, pending)

            val s = stateMapOf(d)
            val meta = d.get("m") as? Map<*, *>
            @Suppress("UNCHECKED_CAST")
            val p = d.get("p") as? Map<String, Any?> ?: emptyMap()
            for ((id, raw) in s) {
                val code = (raw as? Number)?.toLong() ?: continue
                seen += id
                val t = code shr 3
                val local = localStates[id]
                val localT = local?.updatedAt ?: -1
                if (localT < t) {
                    val watched = (code and 1L) != 0L
                    toApply += EpisodeStateEntity(
                        episodeId = id,
                        subscriptionId = local?.subscriptionId ?: d.id,
                        watched = watched,
                        watchLater = (code and 2L) != 0L,
                        favorite = (code and 4L) != 0L,
                        positionMs = (p[id] as? Number)?.toLong() ?: 0,
                        watchedAt = if (watched) local?.watchedAt?.takeIf { it > 0 } ?: t else 0,
                        updatedAt = t,
                    )
                } else if (pending != null && localT > t && localT > 1) {
                    pending.states += local!!.copy(subscriptionId = local.subscriptionId ?: d.id)
                } else if (pending != null && local != null && localT == t && inLibrary(local, false) && meta?.containsKey(id) != true) {
                    // En la Biblioteca pero subido sin sus datos (versiones anteriores): se completa.
                    pending.states += local.copy(subscriptionId = local.subscriptionId ?: d.id)
                }
            }
        }
        if (pending != null) {
            // Cambios hechos a mano en este dispositivo que la nube aún no conoce. Los vistos
            // automáticos del historial (updatedAt <= 1) no se suben: cada dispositivo los calcula.
            localStates.values.filterTo(pending.states) { it.updatedAt > 1 && it.episodeId !in seen && it.subscriptionId != null }
        }
        toApply.chunked(500).forEach { db.states().upsertAll(it) }
        createLibraryEpisodes(docs)
        settings.setStateCursor(maxServer)
    }

    /** Episodios de la Biblioteca que llegan de otro dispositivo y aquí no existen: se crean. */
    private suspend fun createLibraryEpisodes(docs: List<DocumentSnapshot>) {
        for (d in docs) {
            @Suppress("UNCHECKED_CAST")
            val m = d.get("m") as? Map<String, Any?> ?: continue
            if (m.isEmpty() || db.subscriptions().get(d.id) == null) continue
            val present = m.keys.toList().chunked(500).flatMap { db.episodes().existingIds(it) }.toHashSet()
            val missing = m.filterKeys { it !in present }.mapNotNull { (id, raw) ->
                @Suppress("UNCHECKED_CAST")
                val e = raw as? Map<String, Any?> ?: return@mapNotNull null
                EpisodeEntity(
                    id = id,
                    subscriptionId = d.id,
                    title = e["t"] as? String ?: return@mapNotNull null,
                    url = e["u"] as? String ?: return@mapNotNull null,
                    mediaUrl = e["mu"] as? String,
                    mediaType = e["mt"] as? String,
                    thumbnailUrl = e["i"] as? String,
                    publishedAt = (e["d"] as? Number)?.toLong() ?: 0,
                    durationSec = (e["du"] as? Number)?.toLong() ?: 0,
                    isLive = e["lv"] == true,
                    isShort = e["sh"] == true,
                )
            }
            if (missing.isNotEmpty()) db.episodes().upsertAll(missing)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun stateMapOf(d: DocumentSnapshot): Map<String, Any?> = d.get("s") as? Map<String, Any?> ?: emptyMap()

    private suspend fun applyRemoteMeta(d: DocumentSnapshot, pending: Pending?) {
        val remoteUpdated = d.getLong("updatedAt") ?: -1
        val local = db.subscriptions().get(d.id)
        if (local != null && local.updatedAt >= remoteUpdated) {
            if (pending != null && local.updatedAt > remoteUpdated) pending.subs[local.id] = local
            return
        }
        if (d.getBoolean("deleted") == true) {
            if (local != null) {
                db.episodes().deleteForSubscription(d.id)
                db.subscriptions().delete(d.id)
            }
            return
        }
        val sub = SubscriptionEntity(
            id = d.id,
            type = SourceType.fromName(d.getString("type") ?: "RSS"),
            sourceKey = d.getString("sourceKey") ?: return,
            title = d.getString("title") ?: "",
            description = local?.description ?: "",
            imageUrl = d.getString("imageUrl"),
            siteUrl = d.getString("siteUrl"),
            category = d.getString("category"),
            notify = d.getBoolean("notify") ?: true,
            fullHistory = d.getBoolean("fullHistory") ?: false,
            paused = d.getBoolean("paused") ?: false,
            addedAt = d.getLong("addedAt") ?: System.currentTimeMillis(),
            lastRefreshed = local?.lastRefreshed ?: 0,
            lastError = local?.lastError,
            updatedAt = remoteUpdated,
        )
        db.subscriptions().upsert(sub)
        if (local == null) onRemoteSubscriptionAdded?.invoke(sub)
        if (sub.fullHistory && local?.fullHistory != true) onRemoteFullHistory?.invoke(sub)
    }

    // ------------------ Subida de cambios locales ------------------

    private fun subMap(s: SubscriptionEntity): Map<String, Any?> = mapOf(
        "type" to s.type.name,
        "sourceKey" to s.sourceKey,
        "title" to s.title,
        "imageUrl" to s.imageUrl,
        "siteUrl" to s.siteUrl,
        "category" to s.category,
        "notify" to s.notify,
        "fullHistory" to s.fullHistory,
        "paused" to s.paused,
        "addedAt" to s.addedAt,
        "updatedAt" to s.updatedAt,
        "deleted" to false,
    )

    /** Estado de un episodio en un solo número: hora del cambio y visto / ver más tarde / favorito. */
    private fun stateCode(s: EpisodeStateEntity): Long =
        (s.updatedAt shl 3) or (if (s.watched) 1L else 0L) or (if (s.watchLater) 2L else 0L) or (if (s.favorite) 4L else 0L)

    /** Datos de un canal para escribir con merge: solo se tocan los episodios indicados. */
    private fun channelData(
        sub: SubscriptionEntity?,
        states: List<EpisodeStateEntity>,
        episodes: Map<String, EpisodeEntity> = emptyMap(),
    ): Map<String, Any?> = buildMap {
        if (sub != null) putAll(subMap(sub))
        if (states.isNotEmpty()) {
            put("s", states.associate { it.episodeId to stateCode(it) })
            put("p", states.associate { it.episodeId to (if (it.positionMs > 0) it.positionMs else FieldValue.delete()) })
            // "m": datos de los episodios de la Biblioteca; al salir de ella se borran.
            val bulk = isBulk(states)
            val meta = states.mapNotNull { s ->
                val e = episodes[s.episodeId]
                when {
                    inLibrary(s, bulk) && e != null -> s.episodeId to episodeMeta(e)
                    !inLibrary(s, bulk) -> s.episodeId to FieldValue.delete()
                    else -> null
                }
            }.toMap()
            if (meta.isNotEmpty()) put("m", meta)
        }
        put("serverUpdatedAt", FieldValue.serverTimestamp())
    }

    fun pushSubscription(s: SubscriptionEntity) = safely {
        val u = uid ?: return@safely
        track(channels(u).document(s.id).set(channelData(s, emptyList()), SetOptions.merge()))
    }

    fun pushSubscriptionDeleted(id: String, at: Long) = safely {
        val u = uid ?: return@safely
        track(channels(u).document(id).set(
            mapOf("deleted" to true, "updatedAt" to at, "serverUpdatedAt" to FieldValue.serverTimestamp()),
            SetOptions.merge(),
        ))
    }

    /**
     * Una sola escritura por canal, con todos sus episodios cambiados. Las escrituras se
     * encolan offline y Firestore las envía al recuperar conexión.
     */
    fun pushStates(states: List<EpisodeStateEntity>) {
        val u = uid ?: return
        // En orden (un solo hilo): dos cambios seguidos del mismo episodio no se adelantan.
        scope.launch(pushDispatcher) {
            runCatching {
                val fs = firestore ?: return@runCatching
                val byChannel = states.filter { it.subscriptionId != null }.groupBy { it.subscriptionId!! }
                val episodes = libraryEpisodes(byChannel)
                byChannel.entries.chunked(400).forEach { chunk ->
                    val batch = fs.batch()
                    chunk.forEach { (subId, list) -> batch.set(channels(u).document(subId), channelData(null, list, episodes), SetOptions.merge()) }
                    track(batch.commit())
                }
            }.onFailure { Log.w(TAG, "pushStates", it) }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val pushDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)

    /**
     * ¿Está en la Biblioteca? Para esos episodios se envían también sus datos (título, enlace,
     * imagen…), así aparecen en otro dispositivo aunque no los tenga. Del historial solo lo
     * reciente y no en marcados masivos (para no llenar el documento del canal).
     */
    private fun inLibrary(s: EpisodeStateEntity, bulk: Boolean): Boolean =
        s.watchLater || s.favorite || (s.positionMs > 0 && !s.watched) ||
            (s.watched && !bulk && s.watchedAt > System.currentTimeMillis() - 30L * 24 * 3600_000)

    private fun isBulk(list: List<EpisodeStateEntity>) = list.size > 50

    /** Datos de los episodios de la Biblioteca que se van a subir. */
    private suspend fun libraryEpisodes(byChannel: Map<String, List<EpisodeStateEntity>>): Map<String, EpisodeEntity> {
        val ids = byChannel.values.flatMap { list -> list.filter { inLibrary(it, isBulk(list)) }.map { it.episodeId } }
        return ids.chunked(500).flatMap { db.episodes().getMany(it) }.associateBy { it.id }
    }

    private fun episodeMeta(e: EpisodeEntity): Map<String, Any> = buildMap {
        put("t", e.title)
        put("u", e.url)
        put("d", e.publishedAt)
        e.thumbnailUrl?.let { put("i", it) }
        e.mediaUrl?.let { put("mu", it) }
        e.mediaType?.let { put("mt", it) }
        if (e.durationSec > 0) put("du", e.durationSec)
        if (e.isShort) put("sh", true)
        if (e.isLive) put("lv", true)
    }

    /** Mientras se vacía la cola antigua no hay cliente de Firestore: se ignora el error. */
    private inline fun safely(block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "push", it) }
    }

    fun pushProfile(nickname: String?, photoBase64: String?, photoUpdatedAt: Long?) = safely {
        val u = uid ?: return@safely
        val data = buildMap<String, Any?> {
            if (nickname != null) put("nickname", nickname)
            if (photoUpdatedAt != null) {
                put("photo", photoBase64)
                put("photoUpdatedAt", photoUpdatedAt)
            }
        }
        if (data.isNotEmpty()) track(userDoc(u).set(data, SetOptions.merge()))
    }

    /**
     * Primera sincronización en este dispositivo: se combinan datos locales y remotos con
     * una lectura y, como mucho, una escritura por canal. Gana siempre el cambio más reciente.
     */
    private suspend fun uploadAll(u: String) {
        val fs = firestore ?: return
        // Del servidor, no de la caché: sin conexión no se sabría qué hay en la nube.
        val remote = withTimeout(60_000) { channels(u).get(Source.SERVER).await().documents }
        val remoteIds = remote.mapTo(HashSet()) { it.id }
        val pending = Pending()
        applyRemoteChannels(remote, pending)
        db.subscriptions().getAll().filter { it.id !in remoteIds }.forEach { pending.subs[it.id] = it }

        val statesByChannel = pending.states.groupBy { it.subscriptionId!! }
        val episodes = libraryEpisodes(statesByChannel)
        val channelIds = pending.subs.keys + statesByChannel.keys
        channelIds.chunked(400).forEach { chunk ->
            val batch = fs.batch()
            chunk.forEach { id ->
                batch.set(channels(u).document(id), channelData(pending.subs[id], statesByChannel[id].orEmpty(), episodes), SetOptions.merge())
            }
            // Si Firebase tarda (p. ej. límite diario agotado) la escritura queda en cola y se
            // envía sola más tarde: no se repite para no duplicarla.
            withTimeoutOrNull(30_000) { batch.commit().await() }
        }
    }

    companion object {
        private const val TAG = "CloudSync"
    }
}
