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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    val radio = com.feedvibe.app.radio.RadioPlayer(context, settings, appScope)
    val access = com.feedvibe.app.data.access.AccessManager(auth, settings, appScope, updater.currentVersion)

    init {
        cloud.onRemoteSubscriptionAdded = { sub ->
            appScope.launch { runCatching { feeds.refreshOne(sub.id) } }
        }
        cloud.onRemoteFullHistory = { sub ->
            feeds.queueFullHistory(sub.id)
        }
        cloud.onRemoteProfile = { nick, photo, stamp ->
            appScope.launch { profile.applyRemote(nick, photo, stamp) }
        }
        // La sincronización en tiempo real solo funciona con la app a la vista (ahorra batería).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                cloud.setForeground(true)
                onAppOpened()
            }
            override fun onStop(owner: LifecycleOwner) = cloud.setForeground(false)
        })
        access.start()
        // Arranca/para la sincronización según haya sesión iniciada.
        appScope.launch {
            auth.user.collect { u ->
                if (u != null) cloud.start(u.uid) else cloud.stop()
            }
        }
    }

    private var lastOpenCheck = 0L

    /**
     * Cada vez que se abre la app (o se vuelve a ella): buscar actualizaciones de la app y
     * episodios nuevos. Lo marcado en otros dispositivos llega solo por la escucha en tiempo real.
     */
    /** Se ha cerrado la app del todo (quitada de recientes o salida con Atrás). */
    fun appClosed() {
        notifier.clearAll()
        appScope.launch { settings.setAppClosed(true) }
    }

    private fun onAppOpened() = appScope.launch {
        settings.setAppClosed(false)
        val now = System.currentTimeMillis()
        // Si se vuelve a la app enseguida (p. ej. tras ver un vídeo) no se repite.
        if (now - lastOpenCheck < 60_000) return@launch
        lastOpenCheck = now
        val s = settings.current()
        val busy = updater.state.value.let { it is com.feedvibe.app.update.UpdateState.Downloading || it is com.feedvibe.app.update.UpdateState.Installing }
        if (s.autoUpdateCheck && !busy) {
            settings.setLastUpdateCheck(now)
            launch { runCatching { updater.check() } }
        }
        if (s.refreshOnOpen && now - settings.lastRefresh.first() > 5 * 60_000) runCatching { feeds.refreshAll() }
    }

    private var backgroundStarted = false

    /**
     * Tareas al abrir la app:
     * - Reparar una vez los vídeos antiguos que entraron como «sin ver».
     * - Mantener el número de episodios sin ver en el icono de la app.
     */
    fun startBackgroundJobs() {
        if (backgroundStarted) return
        backgroundStarted = true
        appScope.launch {
            if (!settings.repairWatchedDone()) {
                runCatching { feeds.repairOldUnwatched() }
                settings.setRepairWatchedDone()
            }
            feeds.pruneWatched()
            // Ya no se cargan automáticamente todos los vídeos de los canales al abrir: con muchos
            // canales la app se atascaba. Se hace al añadir un canal o desde su menú.
        }
        appScope.launch {
            // Aviso fijo (y número del icono): «N episodios disponibles», con barra de progreso
            // mientras se buscan episodios nuevos y animado mientras se sincroniza.
            combine(
                feeds.unwatchedCount,
                settings.settings.map { it.iconBadge },
                settings.appClosed,
                feeds.refreshProgress,
                cloud.syncing,
            ) { n, on, closed, progress, syncing ->
                if (closed) com.feedvibe.app.notify.BadgeState(0, null, false)
                else com.feedvibe.app.notify.BadgeState(if (on) n else 0, progress, syncing)
            }
                .distinctUntilChanged()
                .conflate()
                .collect {
                    notifier.updateBadge(it)
                    // Como mucho una actualización por segundo (Android limita las notificaciones).
                    kotlinx.coroutines.delay(1000)
                }
        }
    }

    fun ensureSyncStarted() {
        auth.currentUid?.let { cloud.start(it) }
    }
}
