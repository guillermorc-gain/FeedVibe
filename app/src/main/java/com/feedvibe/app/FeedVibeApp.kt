package com.feedvibe.app

import android.app.Application
import com.feedvibe.app.work.WorkScheduler
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class FeedVibeApp : Application() {
    lateinit var container: AppContainer
        private set

    // Lo antes posible: también se registran los fallos al iniciar componentes previos a onCreate.
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        CrashReport.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        CrashReport.note("Inicio de la app ${BuildConfig.VERSION_NAME}")
        container = AppContainer(this)
        container.notifier.createChannels()
        // Reprograma los trabajos periódicos cuando cambian los ajustes.
        container.appScope.launch {
            container.settings.settings
                .map { Triple(it.syncIntervalMin, it.wifiOnly, it.autoBackupDays to it.autoUpdateCheck) }
                .distinctUntilChanged()
                .collect { (interval, wifi, other) ->
                    WorkScheduler.scheduleRefresh(this@FeedVibeApp, interval, wifi)
                    WorkScheduler.scheduleBackup(this@FeedVibeApp, other.first)
                    WorkScheduler.scheduleUpdateCheck(this@FeedVibeApp, other.second)
                }
        }
    }
}
