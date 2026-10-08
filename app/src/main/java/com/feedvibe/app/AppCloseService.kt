package com.feedvibe.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Solo sirve para enterarse de que la app se ha cerrado del todo (al quitarla de las apps
 * recientes): entonces se quitan las notificaciones de episodios sin ver y el número del icono.
 */
class AppCloseService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as FeedVibeApp).container.appClosed()
        stopSelf()
    }
}
