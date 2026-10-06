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
    private var previousTrail = ""
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")
    private fun prefs(context: Context) = context.getSharedPreferences("crash_report", Context.MODE_PRIVATE)
    private fun now() = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("es")).format(Date())
    private fun trailFile(context: Context) = File(context.filesDir, "crash_trail.txt")
    private val trail = ArrayDeque<String>()
    private var appContext: Context? = null

    /**
     * Rastro de lo último que hizo la app (pantallas, actualizaciones…). Se guarda en disco en
     * cada paso para poder verlo aunque el cierre no deje detalles (fallos nativos).
     */
    @Synchronized
    fun note(what: String) {
        val ctx = appContext ?: return
        val line = "${SimpleDateFormat("HH:mm:ss", Locale("es")).format(Date())} $what"
        trail.addLast(line)
        while (trail.size > 25) trail.removeFirst()
        runCatching { trailFile(ctx).writeText(trail.joinToString("\n")) }
    }

    private fun lastTrail(context: Context) =
        runCatching { trailFile(context).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    /** Errores no capturados: se escribe el informe y se deja que Android cierre la app. */
    fun install(context: Context) {
        appContext = context
        // El rastro de la sesión anterior se lee antes de empezar el nuevo.
        previousTrail = lastTrail(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file(context).writeText(
                    "FeedVibe ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "${now()} · hilo ${thread.name}\n\n${error.stackTraceToString().take(12_000)}" +
                        "\n\nÚltimos pasos:\n${trail.joinToString("\n")}"
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
            ApplicationExitInfo.REASON_CRASH -> "Error (Java, sin detalles)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Error nativo"
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
            "${info.description.orEmpty()} · proceso ${info.processName}\n\n$trace" +
            "\n\nÚltimos pasos:\n$previousTrail"
    }

    fun clear(context: Context) {
        file(context).delete()
    }
}
