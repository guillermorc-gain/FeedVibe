package com.feedvibe.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.feedvibe.app.FeedVibeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Acciones de las notificaciones ("Marcar como visto", "Ver más tarde"). */
class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_MARK_WATCHED = "com.feedvibe.app.MARK_WATCHED"
        const val ACTION_WATCH_LATER = "com.feedvibe.app.WATCH_LATER"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val episodeId = intent.getStringExtra(Notifier.EXTRA_EPISODE_ID) ?: return
        val notificationId = intent.getIntExtra(Notifier.EXTRA_NOTIFICATION_ID, 0)
        val container = (context.applicationContext as FeedVibeApp).container
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                container.ensureSyncStarted()
                when (intent.action) {
                    ACTION_MARK_WATCHED -> container.feeds.setWatched(listOf(episodeId), true)
                    ACTION_WATCH_LATER -> container.feeds.getEpisode(episodeId)?.let {
                        if (!it.watchLater) container.feeds.toggleWatchLater(it)
                    }
                }
                container.notifier.cancel(notificationId)
            } finally {
                pending.finish()
            }
        }
    }
}
