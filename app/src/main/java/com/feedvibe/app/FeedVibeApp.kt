package com.feedvibe.app

import android.app.Application
import com.feedvibe.app.work.WorkScheduler
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class FeedVibeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
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
