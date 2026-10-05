package com.feedvibe.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.feedvibe.app.FeedVibeApp
import com.feedvibe.app.data.backup.DriveAuthRequired
import java.util.concurrent.TimeUnit

/** Actualiza todos los canales en segundo plano y notifica las novedades. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as FeedVibeApp).container
        c.ensureSyncStarted()
        // Primero traemos lo marcado en otros dispositivos para no avisar de algo ya visto.
        c.cloud.pullOnce()
        val result = c.feeds.refreshAll()
        val s = c.settings.current()
        if (s.notificationsEnabled) c.notifier.notifyNewEpisodes(result.newEpisodes, s.notifyLive)
        return Result.success()
    }
}

/** Copia de seguridad automática en Google Drive. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as FeedVibeApp).container
        if (c.auth.currentUid == null) return Result.success()
        return try {
            val token = c.drive.accessToken()
            c.drive.backupAndPrune(token, c.backup.fileName(), c.backup.createJson(), c.settings.current().backupKeep)
            c.settings.setLastBackup(System.currentTimeMillis())
            Result.success()
        } catch (e: DriveAuthRequired) {
            c.notifier.notifySystem(4001, "Copia de seguridad pendiente", "Abre FeedVibe y concede acceso a Google Drive.")
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

/** Comprueba si hay una versión nueva de la app y avisa. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as FeedVibeApp).container
        val state = c.updater.check()
        if (state is com.feedvibe.app.update.UpdateState.Available) {
            c.notifier.notifySystem(4002, "Nueva versión ${state.release.version}", "Toca para actualizar FeedVibe sin salir de la app.")
        }
        return Result.success()
    }
}

object WorkScheduler {
    private const val REFRESH = "refresh"
    private const val REFRESH_NOW = "refresh_now"
    private const val BACKUP = "backup"
    private const val UPDATE = "update_check"

    fun scheduleRefresh(context: Context, intervalMin: Int, wifiOnly: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (intervalMin <= 0) {
            wm.cancelUniqueWork(REFRESH)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(intervalMin.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(REFRESH, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(REFRESH_NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun scheduleBackup(context: Context, days: Int) {
        val wm = WorkManager.getInstance(context)
        if (days <= 0) {
            wm.cancelUniqueWork(BACKUP)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(days.toLong(), TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        wm.enqueueUniquePeriodicWork(BACKUP, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun scheduleUpdateCheck(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(UPDATE)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(UPDATE, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
