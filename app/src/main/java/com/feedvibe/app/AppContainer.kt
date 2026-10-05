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

/** Inyección de dependencias manual. */
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

    fun ensureSyncStarted() {
        auth.currentUid?.let { cloud.start(it) }
    }
}
