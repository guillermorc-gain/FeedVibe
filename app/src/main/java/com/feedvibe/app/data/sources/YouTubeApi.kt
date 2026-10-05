package com.feedvibe.app.data.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.Duration

/**
 * YouTube Data API v3. El RSS público de YouTube solo devuelve los 15 últimos vídeos;
 * con la API se recorre la lista de subidas del canal entera (50 vídeos por página).
 *
 * Coste de cuota: 2 unidades por cada 50 vídeos (la cuota gratuita es de 10.000 al día),
 * así que cargar un canal de 1.000 vídeos gasta unas 40 unidades.
 */
object YouTubeApi {
    private const val API = "https://www.googleapis.com/youtube/v3"
    private val UNAVAILABLE = setOf("Private video", "Deleted video", "Vídeo privado", "Vídeo eliminado")

    /** Lista de subidas: UU… (todo) o UULF… (solo vídeos normales, sin Shorts ni directos). */
    fun uploadsPlaylist(key: String, hideShorts: Boolean): String {
        val (kind, id) = key.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (kind == "playlist") return id
        return (if (hideShorts) "UULF" else "UU") + id.removePrefix("UC")
    }

    private suspend fun call(apiKey: String, path: String, params: Map<String, String>): kotlinx.serialization.json.JsonElement =
        withContext(Dispatchers.IO) {
            val url = "$API/$path".toHttpUrl().newBuilder().apply {
                params.forEach { (k, v) -> addQueryParameter(k, v) }
                addQueryParameter("key", apiKey)
            }.build()
            val body = try {
                Http.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        // No mostramos la URL: contiene la clave.
                        val json = runCatching { AppJson.parseToJsonElement(text) }.getOrNull()
                        val reason = json.obj("error").arr("errors")?.firstOrNull().str("reason")
                        throw SourceException(
                            when (reason) {
                                "quotaExceeded" -> "Se ha agotado la cuota diaria de la API de YouTube. Prueba mañana."
                                "keyInvalid", "badRequest" -> "La clave de la API de YouTube no es válida."
                                "playlistNotFound" -> "YouTube no encuentra la lista de vídeos de este canal."
                                "accessNotConfigured", "forbidden" -> "Activa «YouTube Data API v3» en Google Cloud para esa clave."
                                else -> json.obj("error").str("message") ?: "Error ${resp.code} de la API de YouTube"
                            }
                        )
                    }
                    text
                }
            } catch (e: SourceException) {
                throw e
            } catch (e: Exception) {
                throw SourceException("No se pudo conectar con YouTube: ${e.message ?: e.javaClass.simpleName}", e)
            }
            AppJson.parseToJsonElement(body)
        }

    /** Duración ISO-8601 ("PT1H2M3S") a segundos. */
    private fun isoDuration(v: String?): Long = runCatching { Duration.parse(v).seconds }.getOrDefault(0)

    /** Una página (hasta 50 vídeos) de la lista de subidas, con duraciones. */
    private suspend fun page(apiKey: String, playlistId: String, pageToken: String?): Pair<List<ParsedEpisode>, String?> {
        val params = buildMap {
            put("part", "snippet,contentDetails")
            put("playlistId", playlistId)
            put("maxResults", "50")
            if (pageToken != null) put("pageToken", pageToken)
        }
        val json = call(apiKey, "playlistItems", params)
        val items = json.arr("items").orEmpty().mapNotNull { item ->
            val snippet = item.obj("snippet")
            val details = item.obj("contentDetails")
            val videoId = details.str("videoId") ?: return@mapNotNull null
            val title = snippet.str("title") ?: return@mapNotNull null
            if (title in UNAVAILABLE) return@mapNotNull null
            val thumbs = snippet.obj("thumbnails")
            ParsedEpisode(
                guid = videoId, // igual que en el RSS: así no se duplican episodios
                title = title,
                url = "https://www.youtube.com/watch?v=$videoId",
                description = snippet.str("description").orEmpty(),
                thumbnailUrl = (thumbs.obj("high") ?: thumbs.obj("medium") ?: thumbs.obj("default")).str("url")
                    ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                publishedAt = Dates.parse(details.str("videoPublishedAt") ?: snippet.str("publishedAt")) ?: 0,
            )
        }
        val durations = if (items.isEmpty()) emptyMap() else {
            call(apiKey, "videos", mapOf("part" to "contentDetails", "id" to items.joinToString(",") { it.guid }))
                .arr("items").orEmpty()
                .associate { (it.str("id") ?: "") to isoDuration(it.obj("contentDetails").str("duration")) }
        }
        return items.map { it.copy(durationSec = durations[it.guid] ?: 0) } to json.str("nextPageToken")
    }

    /**
     * Descarga todos los vídeos del canal/lista. [onProgress] recibe cuántos lleva.
     * [limit] evita gastar demasiada cuota en canales enormes.
     */
    suspend fun fetchAll(
        apiKey: String,
        key: String,
        hideShorts: Boolean,
        limit: Int = 5000,
        onProgress: (Int) -> Unit = {},
    ): List<ParsedEpisode> {
        var playlist = uploadsPlaylist(key, hideShorts)
        val all = mutableListOf<ParsedEpisode>()
        var token: String? = null
        do {
            val (items, next) = try {
                page(apiKey, playlist, token)
            } catch (e: SourceException) {
                // Algunos canales no tienen la lista UULF: probamos con todas las subidas.
                if (all.isEmpty() && token == null && playlist.startsWith("UULF")) {
                    playlist = uploadsPlaylist(key, false)
                    page(apiKey, playlist, null)
                } else throw e
            }
            all += items
            onProgress(all.size)
            token = next
        } while (token != null && all.size < limit)
        return all
    }

    /** Comprueba la clave haciendo una llamada barata (1 unidad). */
    suspend fun validateKey(apiKey: String) {
        call(apiKey, "videos", mapOf("part" to "id", "id" to "dQw4w9WgXcQ"))
    }
}
