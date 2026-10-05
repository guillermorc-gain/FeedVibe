package com.feedvibe.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.feedvibe.app.FeedVibeApp

/** Recibe el resultado de PackageInstaller y muestra la confirmación del sistema si hace falta. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Instalación cancelada ($status)"
                (context.applicationContext as? FeedVibeApp)?.container?.updater?.onInstallFailed(msg)
            }
        }
    }
}
