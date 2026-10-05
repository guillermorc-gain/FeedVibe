package com.feedvibe.app.data.sync

import android.util.Log
import com.feedvibe.app.data.db.AppDatabase
import com.feedvibe.app.data.db.EpisodeStateEntity
import com.feedvibe.app.data.db.SubscriptionEntity
import com.feedvibe.app.data.prefs.SettingsRepository
import com.feedvibe.app.data.sources.SourceType
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date

enum class SyncStatus { OFF, CONNECTING, SYNCED, ERROR }

/**
 * Sincronización en tiempo real entre dispositivos con Cloud Firestore.
 *
 * users/{uid}                       -> perfil (apodo, foto)
 * users/{uid}/subscriptions/{subId} -> suscripciones (con borrado lógico "deleted")
 * users/{uid}/states/{episodeId}    -> visto / ver más tarde / favorito / posición
 *
 * Cada documento lleva "updatedAt" (hora del dispositivo, para resolver conflictos: gana
 * el cambio más reciente) y "serverUpdatedAt" (hora del servidor, para descargar solo lo
 * que ha cambiado desde la última vez).
 */
class CloudSync(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val available: Boolean,
) {
    private val firestore: FirebaseFirestore? = if (available) FirebaseFirestore.getInstance() else null
    private val listeners = mutableListOf<ListenerRegistration>()
    private var uid: String? = null

    private val _status = MutableStateFlow(SyncStatus.OFF)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Llamado cuando llega una suscripción nueva desde otro dispositivo. */
    var onRemoteSubscriptionAdded: ((SubscriptionEntity) -> Unit)? = null
    var onRemoteProfile: ((nickname: String?, photoBase64: String?, photoUpdatedAt: Long) -> Unit)? = null

    private fun userDoc(u: String) = firestore!!.collection("users").document(u)

    @Synchronized
    fun start(newUid: String) {
        if (firestore == null || uid == newUid) return
        stop()
        uid = newUid
        _status.value = SyncStatus.CONNECTING
        scope.launch {
            // Primer inicio de sesión de este usuario en este dispositivo: subir lo local.
            if (settings.syncedUid() != newUid) {
                settings.setStateCursor(0)
                runCatching { uploadAll(newUid) }.onFailure { Log.w(TAG, "uploadAll", it) }
                settings.setSyncedUid(newUid)
            }
            attachListeners(newUid)
        }
    }

    @Synchronized
    fun stop() {
        listeners.forEach { it.remove() }
        listeners.clear()
        uid = null
        _status.value = SyncStatus.OFF
    }

    suspend fun signedOut() {
        stop()
        settings.setSyncedUid(null)
        settings.setStateCursor(0)
    }

    private suspend fun attachListeners(u: String) {
        if (firestore == null) return
        val cursor = settings.stateCursor()
        listeners += userDoc(u).collection("subscriptions").addSnapshotListener { snap, err ->
            if (err != null) { _status.value = SyncStatus.ERROR; Log.w(TAG, "subs", err); return@addSnapshotListener }
            val docs = snap?.documentChanges?.filter { it.type != DocumentChange.Type.REMOVED }?.map { it.document } ?: return@addSnapshotListener
            scope.launch { applyRemoteSubscriptions(docs) }
            _status.value = SyncStatus.SYNCED
        }
        // Margen de 10 minutos por si los relojes no van exactamente igual.
        val since = Timestamp(Date((cursor - 10 * 60_000).coerceAtLeast(0)))
        listeners += userDoc(u).collection("states")
            .whereGreaterThan("serverUpdatedAt", since)
            .addSnapshotListener { snap, err ->
                if (err != null) { _status.value = SyncStatus.ERROR; Log.w(TAG, "states", err); return@addSnapshotListener }
                val docs = snap?.documentChanges?.filter { it.type != DocumentChange.Type.REMOVED }?.map { it.document } ?: return@addSnapshotListener
                scope.launch { applyRemoteStates(docs) }
                _status.value = SyncStatus.SYNCED
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
        if (firestore == null) return
        runCatching {
            val subs = userDoc(u).collection("subscriptions").get().await()
            applyRemoteSubscriptions(subs.documents)
            val since = Timestamp(Date((settings.stateCursor() - 10 * 60_000).coerceAtLeast(0)))
            val states = userDoc(u).collection("states").whereGreaterThan("serverUpdatedAt", since).get().await()
            applyRemoteStates(states.documents)
        }.onFailure { Log.w(TAG, "pullOnce", it) }
    }

    private suspend fun applyRemoteSubscriptions(docs: List<DocumentSnapshot>) {
        for (d in docs) {
            val remoteUpdated = d.getLong("updatedAt") ?: 0
            val local = db.subscriptions().get(d.id)
            if (local != null && local.updatedAt >= remoteUpdated) continue
            if (d.getBoolean("deleted") == true) {
                if (local != null) {
                    db.episodes().deleteForSubscription(d.id)
                    db.subscriptions().delete(d.id)
                }
                continue
            }
            val sub = SubscriptionEntity(
                id = d.id,
                type = SourceType.fromName(d.getString("type") ?: "RSS"),
                sourceKey = d.getString("sourceKey") ?: continue,
                title = d.getString("title") ?: "",
                description = local?.description ?: "",
                imageUrl = d.getString("imageUrl"),
                siteUrl = d.getString("siteUrl"),
                category = d.getString("category"),
                notify = d.getBoolean("notify") ?: true,
                addedAt = d.getLong("addedAt") ?: System.currentTimeMillis(),
                lastRefreshed = local?.lastRefreshed ?: 0,
                lastError = local?.lastError,
                updatedAt = remoteUpdated,
            )
            db.subscriptions().upsert(sub)
            if (local == null) onRemoteSubscriptionAdded?.invoke(sub)
        }
    }

    private suspend fun applyRemoteStates(docs: List<DocumentSnapshot>) {
        if (docs.isEmpty()) return
        var maxServer = settings.stateCursor()
        val states = docs.map { d ->
            d.getTimestamp("serverUpdatedAt")?.toDate()?.time?.let { if (it > maxServer) maxServer = it }
            EpisodeStateEntity(
                episodeId = d.id,
                subscriptionId = d.getString("subscriptionId"),
                watched = d.getBoolean("watched") ?: false,
                watchLater = d.getBoolean("watchLater") ?: false,
                favorite = d.getBoolean("favorite") ?: false,
                positionMs = d.getLong("positionMs") ?: 0,
                watchedAt = d.getLong("watchedAt") ?: 0,
                updatedAt = d.getLong("updatedAt") ?: 0,
            )
        }
        db.states().upsertIfNewer(states)
        settings.setStateCursor(maxServer)
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
        "addedAt" to s.addedAt,
        "updatedAt" to s.updatedAt,
        "deleted" to false,
        "serverUpdatedAt" to FieldValue.serverTimestamp(),
    )

    private fun stateMap(s: EpisodeStateEntity): Map<String, Any?> = mapOf(
        "subscriptionId" to s.subscriptionId,
        "watched" to s.watched,
        "watchLater" to s.watchLater,
        "favorite" to s.favorite,
        "positionMs" to s.positionMs,
        "watchedAt" to s.watchedAt,
        "updatedAt" to s.updatedAt,
        "serverUpdatedAt" to FieldValue.serverTimestamp(),
    )

    fun pushSubscription(s: SubscriptionEntity) {
        val u = uid ?: return
        userDoc(u).collection("subscriptions").document(s.id).set(subMap(s))
    }

    fun pushSubscriptionDeleted(id: String, at: Long) {
        val u = uid ?: return
        userDoc(u).collection("subscriptions").document(id).set(
            mapOf("deleted" to true, "updatedAt" to at, "serverUpdatedAt" to FieldValue.serverTimestamp()),
            SetOptions.merge(),
        )
    }

    /** Las escrituras se encolan offline y Firestore las envía al recuperar conexión. */
    fun pushStates(states: List<EpisodeStateEntity>) {
        val u = uid ?: return
        val fs = firestore ?: return
        states.chunked(400).forEach { chunk ->
            val batch = fs.batch()
            chunk.forEach { batch.set(userDoc(u).collection("states").document(it.episodeId), stateMap(it)) }
            batch.commit()
        }
    }

    fun pushProfile(nickname: String?, photoBase64: String?, photoUpdatedAt: Long?) {
        val u = uid ?: return
        val data = buildMap<String, Any?> {
            if (nickname != null) put("nickname", nickname)
            if (photoUpdatedAt != null) {
                put("photo", photoBase64)
                put("photoUpdatedAt", photoUpdatedAt)
            }
        }
        if (data.isNotEmpty()) userDoc(u).set(data, SetOptions.merge())
    }

    /**
     * Primera sincronización en este dispositivo: se combinan datos locales y remotos.
     * Gana siempre el cambio más reciente de cada elemento.
     */
    private suspend fun uploadAll(u: String) {
        val fs = firestore ?: return
        val remoteSubs = userDoc(u).collection("subscriptions").get().await().documents
        val remoteSubTimes = remoteSubs.associate { it.id to (it.getLong("updatedAt") ?: -1) }
        val localSubs = db.subscriptions().getAll().filter { s -> (remoteSubTimes[s.id] ?: -1) < s.updatedAt }
        applyRemoteSubscriptions(remoteSubs)
        localSubs.chunked(400).forEach { chunk ->
            val batch = fs.batch()
            chunk.forEach { batch.set(userDoc(u).collection("subscriptions").document(it.id), subMap(it)) }
            batch.commit().await()
        }

        val remoteStates = userDoc(u).collection("states").get().await().documents
        val remoteStateTimes = remoteStates.associate { it.id to (it.getLong("updatedAt") ?: -1) }
        val localStates = db.states().getAll().filter { s -> (remoteStateTimes[s.episodeId] ?: -1) < s.updatedAt }
        applyRemoteStates(remoteStates)
        localStates.chunked(400).forEach { chunk ->
            val batch = fs.batch()
            chunk.forEach { batch.set(userDoc(u).collection("states").document(it.episodeId), stateMap(it)) }
            batch.commit().await()
        }
    }

    companion object {
        private const val TAG = "CloudSync"
    }
}
