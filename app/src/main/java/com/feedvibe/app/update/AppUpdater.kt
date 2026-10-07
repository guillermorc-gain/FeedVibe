package com.feedvibe.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.feedvibe.app.BuildConfig
import com.feedvibe.app.data.sources.AppJson
import com.feedvibe.app.data.sources.Http
import com.feedvibe.app.data.sources.arr
import com.feedvibe.app.data.sources.long
import com.feedvibe.app.data.sources.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

data class ReleaseInfo(val version: String, val notes: String, val apkUrl: String, val size: Long, val pageUrl: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * Actualización desde dentro de la app: consulta la última Release de GitHub,
 * descarga el APK y lo instala con PackageInstaller sin salir de la aplicación
 * (Android solo pide una confirmación).
 */
class AppUpdater(private val context: Context) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String = BuildConfig.VERSION_NAME

    suspend fun check(): UpdateState {
        _state.value = UpdateState.Checking
        val result = try {
            // La API de GitHub solo permite 60 consultas por hora sin cuenta (error 403):
            // si falla se usa la página web de la última versión, que no tiene ese límite.
            val info = runCatching { latestFromApi() }.getOrElse { latestFromWeb() }
            if (isNewer(info.version, currentVersion)) UpdateState.Available(info) else UpdateState.UpToDate
        } catch (e: Exception) {
            UpdateState.Error(e.message ?: "No se pudo comprobar")
        }
        _state.value = result
        return result
    }

    private suspend fun latestFromApi(): ReleaseInfo {
        val json = AppJson.parseToJsonElement(
            Http.get(
                "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest",
                mapOf("Accept" to "application/vnd.github+json"),
            ).body
        )
        val tag = json.str("tag_name") ?: error("Sin versiones publicadas")
        val apk = json.arr("assets").orEmpty().firstOrNull { it.str("name")?.endsWith(".apk") == true }
            ?: error("La versión $tag no tiene APK")
        return ReleaseInfo(
            version = tag.removePrefix("v"),
            notes = json.str("body").orEmpty(),
            apkUrl = apk.str("browser_download_url") ?: error("APK sin URL"),
            size = apk.long("size") ?: 0,
            pageUrl = json.str("html_url").orEmpty(),
        )
    }

    /** github.com/…/releases/latest redirige a …/releases/tag/vX.Y.Z; el APK se llama FeedVibe-X.Y.Z.apk. */
    private suspend fun latestFromWeb(): ReleaseInfo {
        val repo = BuildConfig.UPDATE_REPO
        val page = Http.get("https://github.com/$repo/releases/latest").finalUrl
        val tag = page.substringAfter("/releases/tag/", "").substringBefore('?').ifBlank { error("Sin versiones publicadas") }
        val version = tag.removePrefix("v")
        return ReleaseInfo(
            version = version,
            notes = "",
            apkUrl = "https://github.com/$repo/releases/download/$tag/FeedVibe-$version.apk",
            size = 0,
            pageUrl = page,
        )
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun downloadAndInstall(release: ReleaseInfo) {
        try {
            val file = download(release)
            _state.value = UpdateState.Installing(release)
            install(file)
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.message ?: "Error al actualizar")
        }
    }

    private suspend fun download(release: ReleaseInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "feedvibe-${release.version}.apk")
        _state.value = UpdateState.Downloading(release, 0f)
        Http.client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("Descarga fallida (${resp.code})")
            val body = resp.body ?: error("Descarga vacía")
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    var lastEmit = 0L
                    while (input.read(buf).also { read = it } >= 0) {
                        out.write(buf, 0, read)
                        done += read
                        if (total > 0 && done - lastEmit > 256 * 1024) {
                            lastEmit = done
                            _state.value = UpdateState.Downloading(release, done.toFloat() / total)
                        }
                    }
                }
            }
        }
        file
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    internal fun onInstallFailed(message: String) {
        _state.value = UpdateState.Error(message)
    }

    companion object {
        /** Compara "1.2.10" con "1.2.9" numéricamente. */
        fun isNewer(remote: String, local: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val r = parts(remote)
            val l = parts(local)
            for (i in 0 until maxOf(r.size, l.size)) {
                val a = r.getOrElse(i) { 0 }
                val b = l.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }
    }
}
