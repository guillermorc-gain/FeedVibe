package com.feedvibe.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guarda por qué se cerró la app la última vez (error, bloqueo o falta de memoria) para
 * poder enseñarlo y compartirlo al volver a abrirla.
 */
object CrashReport {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")
    private fun prefs(context: Context) = context.getSharedPreferences("crash_report", Context.MODE_PRIVATE)
    private fun now() = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("es")).format(Date())

    /** Errores no capturados: se escribe el informe y se deja que Android cierre la app. */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file(context).writeText(
                    "FeedVibe ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "${now()} · hilo ${thread.name}\n\n${error.stackTraceToString().take(12_000)}"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Informe pendiente, si lo hay. En Android 11+ también detecta los cierres que no son
     * errores de la app: «no responde» (ANR) y falta de memoria.
     */
    fun pending(context: Context): String? {
        file(context).takeIf { it.exists() }?.let { return it.readText() }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() }.getOrNull() ?: return null
        val seen = prefs(context).getLong("seen", 0)
        if (info.timestamp <= seen) return null
        prefs(context).edit().putLong("seen", info.timestamp).apply()
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_ANR -> "La app dejó de responder (ANR)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Android la cerró por falta de memoria"
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "Error"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Uso excesivo de recursos"
            else -> return null
        }
        val trace = if (info.reason == ApplicationExitInfo.REASON_ANR) {
            // Solo el hilo principal: es el que estaba bloqueado.
            runCatching {
                info.traceInputStream?.bufferedReader()?.use { r ->
                    r.readText().substringAfter("\"main\"", "").take(6_000)
                }
            }.getOrNull().orEmpty()
        } else ""
        val pss = info.pss / 1024
        return "FeedVibe ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "$reason · ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("es")).format(Date(info.timestamp))} · memoria $pss MB\n" +
            "${info.description.orEmpty()}\n\n$trace"
    }

    fun clear(context: Context) {
        file(context).delete()
    }
}
