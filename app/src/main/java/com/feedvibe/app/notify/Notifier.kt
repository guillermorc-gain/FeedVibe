package com.feedvibe.app.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import android.graphics.drawable.BitmapDrawable
import com.feedvibe.app.MainActivity
import com.feedvibe.app.R
import com.feedvibe.app.data.repo.NewEpisodes

class Notifier(private val context: Context) {

    companion object {
        const val CHANNEL_EPISODES = "episodes"
        const val CHANNEL_LIVE = "live"
        const val CHANNEL_SYSTEM = "system"
        const val CHANNEL_BADGE = "badge_count"
        private val OLD_BADGE_CHANNELS = listOf("badge", "badge_min")
        private const val BADGE_ID = 4100
        private const val GROUP = "com.feedvibe.NEW_EPISODES"
        const val EXTRA_EPISODE_ID = "episode_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_OPEN_TAB = "open_tab"
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_EPISODES, "Nuevos episodios", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Avisos de vídeos y episodios nuevos de tus canales" },
                NotificationChannel(CHANNEL_LIVE, "Directos", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Cuando un canal empieza un directo" },
                NotificationChannel(CHANNEL_SYSTEM, "Copias y actualizaciones", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_BADGE, "Contador de episodios sin ver", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Muestra el número de episodios sin ver en el icono de la app"
                    setShowBadge(true)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        )
        // Canal anterior del contador: los ajustes de un canal no se pueden cambiar una vez creado.
        OLD_BADGE_CHANNELS.forEach { nm.deleteNotificationChannel(it) }
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private suspend fun loadBitmap(url: String?): Bitmap? {
        if (url == null) return null
        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(url).allowHardware(false).build())
        return ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
    }

    private fun openAppIntent(requestCode: Int, episodeId: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            episodeId?.let { putExtra(EXTRA_EPISODE_ID, it) }
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @SuppressLint("MissingPermission")
    suspend fun notifyNewEpisodes(list: List<NewEpisodes>, notifyLive: Boolean) {
        if (!canPost()) return
        val nm = NotificationManagerCompat.from(context)
        var total = 0
        for (n in list) {
            if (!n.subscription.notify) continue
            for (ep in n.episodes.sortedBy { it.publishedAt }.takeLast(5)) {
                if (ep.isLive && !notifyLive) continue
                total++
                val id = ep.id.hashCode()
                val markIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                    action = NotificationActionReceiver.ACTION_MARK_WATCHED
                    putExtra(EXTRA_EPISODE_ID, ep.id)
                    putExtra(EXTRA_NOTIFICATION_ID, id)
                }
                val markPending = PendingIntent.getBroadcast(
                    context, id, markIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val laterIntent = Intent(markIntent).apply { action = NotificationActionReceiver.ACTION_WATCH_LATER }
                val laterPending = PendingIntent.getBroadcast(
                    context, id + 1, laterIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val thumb = runCatching { loadBitmap(ep.thumbnailUrl) }.getOrNull()
                val avatar = runCatching { loadBitmap(n.subscription.imageUrl) }.getOrNull()
                val builder = NotificationCompat.Builder(context, if (ep.isLive) CHANNEL_LIVE else CHANNEL_EPISODES)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(n.subscription.title)
                    .setContentText(ep.title)
                    .setSubText(n.subscription.type.label)
                    .setWhen(ep.publishedAt)
                    .setShowWhen(true)
                    .setAutoCancel(true)
                    .setGroup(GROUP)
                    .setColor(0xFF0A7CF6.toInt())
                    .setContentIntent(openAppIntent(id, ep.id))
                    .addAction(0, "Marcar como visto", markPending)
                    .addAction(0, "Ver más tarde", laterPending)
                if (avatar != null) builder.setLargeIcon(avatar)
                if (thumb != null) {
                    builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(thumb).setSummaryText(ep.title))
                } else {
                    builder.setStyle(NotificationCompat.BigTextStyle().bigText(ep.title))
                }
                runCatching { nm.notify(id, builder.build()) }
            }
        }
        if (total > 1) {
            val summary = NotificationCompat.Builder(context, CHANNEL_EPISODES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$total novedades")
                .setContentText(list.joinToString { it.subscription.title })
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(0))
                .build()
            runCatching { nm.notify(GROUP.hashCode(), summary) }
        }
    }

    @SuppressLint("MissingPermission")
    fun notifySystem(id: Int, title: String, text: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CHANNEL_SYSTEM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(id))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    fun cancel(id: Int) = NotificationManagerCompat.from(context).cancel(id)

    /**
     * Número en el icono de la app. Android no deja poner un número directamente: los launchers
     * (Samsung, Xiaomi…) lo toman del número de una notificación. Se usa una notificación
     * silenciosa y mínima (sin icono en la barra de estado) con setNumber; con 0 se quita.
     * Sin texto o como «secreta», Samsung puede no mostrar el número.
     */
    /** App cerrada del todo: fuera los avisos de episodios y el número del icono. */
    fun clearAll() {
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
        updateBadge(BadgeState(0, null, false))
    }

    @SuppressLint("MissingPermission")
    fun updateBadge(state: BadgeState) {
        val nm = NotificationManagerCompat.from(context)
        val count = state.count
        val progress = state.progress
        if ((count <= 0 && progress == null) || !canPost()) {
            nm.cancel(BADGE_ID)
        } else {
            val b = NotificationCompat.Builder(context, CHANNEL_BADGE)
            when {
                // Iconos animados del sistema: se mueven mientras busca o sincroniza.
                progress != null -> b.setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Buscando nuevos episodios… (${progress.first} de ${progress.second})")
                    .setProgress(progress.second.coerceAtLeast(1), progress.first, false)
                state.syncing -> b.setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("$count episodios disponibles")
                    .setContentText("Sincronizando…")
                else -> b.setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(if (count == 1) "1 episodio disponible" else "$count episodios disponibles")
            }
            val n = b
                .setNumber(count)
                .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openAppIntent(BADGE_ID))
                .build()
            runCatching { nm.notify(BADGE_ID, n) }
        }
        // Launchers antiguos de Samsung/LG/Sony leen este aviso.
        runCatching {
            context.sendBroadcast(Intent("android.intent.action.BADGE_COUNT_UPDATE").apply {
                putExtra("badge_count", count.coerceAtLeast(0))
                putExtra("badge_count_package_name", context.packageName)
                putExtra("badge_count_class_name", MainActivity::class.java.name)
            })
        }
    }
}

/** Lo que muestra el aviso fijo: episodios sin ver, progreso de la búsqueda y si sincroniza. */
data class BadgeState(val count: Int, val progress: Pair<Int, Int>?, val syncing: Boolean)
