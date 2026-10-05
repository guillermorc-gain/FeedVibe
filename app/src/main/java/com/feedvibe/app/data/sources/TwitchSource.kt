package com.feedvibe.app.data.sources

import android.net.Uri
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Twitch: usa la API GraphQL pública de la web (no requiere cuenta de desarrollador).
 * Devuelve el directo actual (si lo hay) y los últimos vídeos/VODs.
 */
object TwitchSource {
    private const val GQL = "https://gql.twitch.tv/gql"
    private const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    private val RESERVED = setOf("directory", "videos", "p", "search", "settings", "downloads", "jobs", "turbo")

    fun matches(input: String) = input.lowercase().contains("twitch.tv")

    fun resolveKey(input: String): String {
        val uri = Uri.parse(if (input.startsWith("http")) input else "https://$input")
        val login = uri.pathSegments.firstOrNull { it.isNotBlank() && it.lowercase() !in RESERVED }
            ?: throw SourceException("URL de Twitch no válida")
        return login.lowercase()
    }

    suspend fun fetch(login: String): ParsedFeed {
        val query = """
            query(${'$'}login: String!) {
              user(login: ${'$'}login) {
                login displayName description profileImageURL(width: 300)
                stream { id title createdAt previewImageURL(width: 640, height: 360) game { name } }
                videos(first: 30, sort: TIME) {
                  edges { node { id title createdAt lengthSeconds previewThumbnailURL(width: 640, height: 360) } }
                }
              }
            }
        """.trimIndent()
        val body = buildJsonObject {
            put("query", query)
            putJsonObject("variables") { put("login", login) }
        }.toString()
        val json = AppJson.parseToJsonElement(Http.postJson(GQL, body, mapOf("Client-ID" to CLIENT_ID)))
        val user = json.obj("data").obj("user") ?: throw SourceException("No existe el canal de Twitch \"$login\"")
        val name = user.str("displayName") ?: login
        val episodes = mutableListOf<ParsedEpisode>()
        user.obj("stream")?.let { s ->
            episodes += ParsedEpisode(
                guid = "live-${s.str("id")}",
                title = "🔴 EN DIRECTO: ${s.str("title") ?: name}",
                url = "https://www.twitch.tv/$login",
                description = s.obj("game").str("name").orEmpty(),
                thumbnailUrl = s.str("previewImageURL"),
                publishedAt = Dates.parse(s.str("createdAt")) ?: System.currentTimeMillis(),
                isLive = true,
            )
        }
        user.obj("videos").arr("edges")?.forEach { edge ->
            val v = edge.obj("node") ?: return@forEach
            val id = v.str("id") ?: return@forEach
            episodes += ParsedEpisode(
                guid = id,
                title = v.str("title") ?: "Vídeo",
                url = "https://www.twitch.tv/videos/$id",
                thumbnailUrl = v.str("previewThumbnailURL"),
                publishedAt = Dates.parse(v.str("createdAt")) ?: 0,
                durationSec = v.long("lengthSeconds") ?: 0,
            )
        }
        return ParsedFeed(
            type = SourceType.TWITCH,
            sourceKey = login,
            title = name,
            description = user.str("description").orEmpty(),
            imageUrl = user.str("profileImageURL"),
            siteUrl = "https://www.twitch.tv/$login",
            episodes = episodes,
        )
    }
}
