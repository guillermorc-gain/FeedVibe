package com.feedvibe.app.update

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.feedvibe.app.BuildConfig
import com.feedvibe.app.MainActivity
import com.feedvibe.app.R

/**
 * Se ejecuta en cuanto termina de instalarse una versión nueva de la app: la vuelve a abrir.
 * Si el sistema no deja abrirla desde segundo plano, queda un aviso para abrirla con un toque.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 4200, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { context.startActivity(open) }.onFailure { Log.w("FeedVibe", "No se pudo reabrir", it) }
        notifyUpdated(context, pending)
    }

    @SuppressLint("MissingPermission")
    private fun notifyUpdated(context: Context, pending: PendingIntent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "App actualizada", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Aviso para volver a abrir FeedVibe tras actualizarse" }
            )
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("FeedVibe se ha actualizado")
            .setContentText("Versión ${BuildConfig.VERSION_NAME} · toca para abrir")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
        // Pantalla completa si el sistema lo permite: abre la app directamente.
        if (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()) builder.setFullScreenIntent(pending, true)
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
    }

    companion object {
        private const val CHANNEL = "updated"
        const val NOTIFICATION_ID = 4201

        /** Al abrir la app ya no hace falta el aviso. */
        fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
