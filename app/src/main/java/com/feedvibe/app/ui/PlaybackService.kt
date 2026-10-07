package com.feedvibe.app.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.feedvibe.app.R

/**
 * Servicio en primer plano mientras se ve un vídeo de YouTube: evita que Android pare la app con
 * la pantalla apagada o en segundo plano, y muestra la notificación de reproducción (también en
 * la pantalla de bloqueo) con pausa, siguiente y cerrar, como YouTube.
 */
class PlaybackService : Service() {
    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 5001
        private var instance: PlaybackService? = null
        private var title = ""
        private var channel = ""
        private var playing = false

        /** Lo llama el reproductor cuando cambia el vídeo o se pausa/reanuda. */
        fun update(context: Context, title: String, channel: String, playing: Boolean) {
            this.title = title
            this.channel = channel
            this.playing = playing
            val service = instance
            if (service != null) {
                service.refresh()
            } else if (playing) {
                // Se arranca al empezar a reproducir (con la app a la vista).
                runCatching { ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java)) }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    private lateinit var session: MediaSession
    // Mientras suena: CPU y Wi‑Fi despiertas (con la pantalla apagada se dormirían y el vídeo se cortaría).
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controles del vídeo que se está reproduciendo"
                setShowBadge(false)
            }
        )
        // Controles de la pantalla de bloqueo, auriculares y relojes.
        session = MediaSession(this, "FeedVibe").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = control(YouTubePlayerActivity.CONTROL_PLAY)
                override fun onPause() = control(YouTubePlayerActivity.CONTROL_PAUSE)
                override fun onSkipToNext() = control(YouTubePlayerActivity.CONTROL_NEXT)
                override fun onStop() = control(YouTubePlayerActivity.CONTROL_CLOSE)
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        refresh()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        setAwake(false)
        session.release()
        super.onDestroy()
    }

    private fun control(control: Int) = sendBroadcast(YouTubePlayerActivity.controlIntent(this, control))

    @Suppress("DEPRECATION")
    private fun setAwake(awake: Boolean) {
        if (awake) {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FeedVibe:playback")
                    .apply { setReferenceCounted(false) }
            }
            if (wifiLock == null) {
                wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FeedVibe:playback")
                    .apply { setReferenceCounted(false) }
            }
            // Con límite por si algo falla; se renueva en cada cambio de estado.
            wakeLock?.acquire(6 * 60 * 60 * 1000L)
            wifiLock?.acquire()
        } else {
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
        }
    }

    private fun refresh() {
        setAwake(playing)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, channel)
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f,
                )
                .build()
        )
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        fun action(control: Int, icon: Int, label: String) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), label,
            PendingIntent.getBroadcast(
                this, 100 + control, YouTubePlayerActivity.controlIntent(this, control),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, YouTubePlayerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { "FeedVibe" })
            .setContentText(channel)
            .setContentIntent(open)
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(
                if (playing) action(YouTubePlayerActivity.CONTROL_PLAY_PAUSE, android.R.drawable.ic_media_pause, "Pausa")
                else action(YouTubePlayerActivity.CONTROL_PLAY_PAUSE, android.R.drawable.ic_media_play, "Reproducir")
            )
            .addAction(action(YouTubePlayerActivity.CONTROL_NEXT, android.R.drawable.ic_media_next, "Siguiente"))
            .addAction(action(YouTubePlayerActivity.CONTROL_CLOSE, android.R.drawable.ic_menu_close_clear_cancel, "Cerrar"))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }
}
