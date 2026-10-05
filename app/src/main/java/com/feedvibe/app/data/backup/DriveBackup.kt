package com.feedvibe.app.data.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.feedvibe.app.data.sources.AppJson
import com.feedvibe.app.data.sources.Http
import com.feedvibe.app.data.sources.arr
import com.feedvibe.app.data.sources.str
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

data class DriveFile(val id: String, val name: String, val createdTime: String, val size: Long)

/** Necesita que el usuario conceda permiso (se muestra una pantalla de Google). */
class DriveAuthRequired(val pendingIntent: PendingIntent) : Exception("Se necesita permiso para usar Google Drive")

/**
 * Copias de seguridad en Google Drive usando la API REST con el permiso "drive.file"
 * (la app solo ve los archivos que ella misma crea, en la carpeta "FeedVibe").
 */
class DriveBackup(private val context: Context) {
    private val scope = Scope("https://www.googleapis.com/auth/drive.file")
    private val api = "https://www.googleapis.com/drive/v3"
    private val folderName = "FeedVibe"

    /** Devuelve un token o lanza [DriveAuthRequired] si hace falta la confirmación del usuario. */
    suspend fun accessToken(): String {
        val request = AuthorizationRequest.Builder().setRequestedScopes(listOf(scope)).build()
        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        if (result.hasResolution()) {
            throw DriveAuthRequired(result.pendingIntent ?: error("Sin intent de autorización"))
        }
        return result.accessToken ?: error("No se obtuvo token de Google Drive")
    }

    /** Llamar con el resultado de la pantalla de permisos. */
    fun tokenFromIntent(data: Intent?): String? = runCatching {
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken
    }.getOrNull()

    private suspend fun call(request: Request): String = withContext(Dispatchers.IO) {
        Http.client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Google Drive respondió ${resp.code}: ${body.take(200)}")
            body
        }
    }

    private fun req(token: String, url: String) = Request.Builder().url(url).header("Authorization", "Bearer $token")

    private suspend fun folderId(token: String): String {
        val q = URLEncoder.encode("name='$folderName' and mimeType='application/vnd.google-apps.folder' and trashed=false", "UTF-8")
        val found = AppJson.parseToJsonElement(call(req(token, "$api/files?q=$q&fields=files(id,name)").build()))
        found.arr("files")?.firstOrNull()?.str("id")?.let { return it }
        val meta = """{"name":"$folderName","mimeType":"application/vnd.google-apps.folder"}"""
        val created = call(req(token, "$api/files?fields=id").post(meta.toRequestBody("application/json".toMediaType())).build())
        return AppJson.parseToJsonElement(created).str("id") ?: error("No se pudo crear la carpeta en Drive")
    }

    suspend fun upload(token: String, name: String, json: String): String {
        val folder = folderId(token)
        val meta = """{"name":"$name","parents":["$folder"],"mimeType":"application/json"}"""
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(json.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = call(
            req(token, "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
                .post(body).build()
        )
        return AppJson.parseToJsonElement(resp).str("id") ?: error("Error al subir la copia")
    }

    suspend fun list(token: String): List<DriveFile> {
        val folder = folderId(token)
        val q = URLEncoder.encode("'$folder' in parents and trashed=false", "UTF-8")
        val json = AppJson.parseToJsonElement(
            call(req(token, "$api/files?q=$q&orderBy=createdTime%20desc&fields=files(id,name,createdTime,size)&pageSize=100").build())
        )
        return json.arr("files").orEmpty().mapNotNull { f ->
            DriveFile(
                id = f.str("id") ?: return@mapNotNull null,
                name = f.str("name").orEmpty(),
                createdTime = f.str("createdTime").orEmpty(),
                size = f.str("size")?.toLongOrNull() ?: 0,
            )
        }
    }

    suspend fun download(token: String, fileId: String): String = call(req(token, "$api/files/$fileId?alt=media").build())

    suspend fun delete(token: String, fileId: String) {
        call(req(token, "$api/files/$fileId").delete().build())
    }

    /** Sube una copia y borra las más antiguas, dejando [keep]. */
    suspend fun backupAndPrune(token: String, name: String, json: String, keep: Int) {
        upload(token, name, json)
        list(token).filter { it.name.startsWith("feedvibe-backup-") }.drop(keep.coerceAtLeast(1)).forEach {
            runCatching { delete(token, it.id) }
        }
    }
}
