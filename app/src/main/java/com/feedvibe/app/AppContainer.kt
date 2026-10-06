package com.feedvibe.app

import android.content.Context
import com.feedvibe.app.data.auth.AuthManager
import com.feedvibe.app.data.backup.BackupManager
import com.feedvibe.app.data.backup.DriveBackup
import com.feedvibe.app.data.db.AppDatabase
import com.feedvibe.app.data.prefs.SettingsRepository
import com.feedvibe.app.data.repo.FeedRepository
import com.feedvibe.app.data.repo.ProfileRepository
import com.feedvibe.app.data.sync.CloudSync
import com.feedvibe.app.notify.Notifier
import com.feedvibe.app.update.AppUpdater
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Inyección de dependencias manual. */
@OptIn(FlowPreview::class)
class AppContainer(val context: Context) {
    /** Ámbito global: un fallo en una tarea no tumba la app, solo se registra. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e("FeedVibe", "Error en segundo plano", e) }
    )
    val db = AppDatabase.create(context)
    val settings = SettingsRepository(context)
    val auth = AuthManager(context)
    val cloud = CloudSync(db, settings, appScope, auth.isAvailable)
    val feeds = FeedRepository(db, settings, cloud)
    val profile = ProfileRepository(context, settings, cloud)
    val backup = BackupManager(feeds, settings)
    val drive = DriveBackup(context)
    val notifier = Notifier(context)
    val updater = AppUpdater(context)

    init {
        cloud.onRemoteSubscriptionAdded = { sub ->
            appScope.launch { runCatching { feeds.refreshOne(sub.id) } }
        }
        cloud.onRemoteFullHistory = { sub ->
            appScope.launch { runCatching { feeds.loadFullHistory(sub.id, markOldWatched = false) } }
        }
        cloud.onRemoteProfile = { nick, photo, stamp ->
            appScope.launch { profile.applyRemote(nick, photo, stamp) }
        }
        // Arranca/para la sincronización según haya sesión iniciada.
        appScope.launch {
            auth.user.collect { u ->
                if (u != null) cloud.start(u.uid) else cloud.stop()
            }
        }
    }

    private var backgroundStarted = false

    /**
     * Tareas al abrir la app:
     * - Cargar todos los vídeos de los canales de YouTube que solo tienen los últimos (importados…).
     *   Una vez se recargan también los ya cargados (títulos en español y marca de Shorts).
     * - Mantener el número de episodios sin ver en el icono de la app.
     */
    fun startBackgroundJobs() {
        if (backgroundStarted) return
        backgroundStarted = true
        appScope.launch {
            val reloadAll = !settings.backfillDone()
            feeds.backfillFullHistory(includeAlreadyLoaded = reloadAll)
            if (reloadAll) settings.setBackfillDone()
        }
        appScope.launch {
            combine(feeds.unwatchedCount, settings.settings.map { it.iconBadge }) { n, on -> if (on) n else 0 }
                .distinctUntilChanged()
                .debounce(1500)
                .collect { notifier.updateBadge(it) }
        }
    }

    fun ensureSyncStarted() {
        auth.currentUid?.let { cloud.start(it) }
    }
}
