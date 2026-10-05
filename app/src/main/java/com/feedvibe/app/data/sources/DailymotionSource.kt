package com.feedvibe.app.data.sources

import android.net.Uri

/** Dailymotion: API pública sin clave. */
object DailymotionSource {
    private const val API = "https://api.dailymotion.com"

    fun matches(input: String) = input.lowercase().let { it.contains("dailymotion.com") || it.contains("dai.ly") }

    suspend fun resolveKey(input: String): String {
        val uri = Uri.parse(if (input.startsWith("http")) input else "https://$input")
        val seg = uri.pathSegments
        val videoId = when {
            uri.host?.contains("dai.ly") == true -> seg.firstOrNull()
            seg.firstOrNull() == "video" -> seg.getOrNull(1)
            else -> null
        }
        if (videoId != null) {
            val json = AppJson.parseToJsonElement(Http.get("$API/video/$videoId?fields=owner.username").body)
            return json.str("owner.username") ?: throw SourceException("No se encontró el canal del vídeo")
        }
        return seg.firstOrNull()?.takeIf { it.isNotBlank() } ?: throw SourceException("URL de Dailymotion no válida")
    }

    suspend fun fetch(username: String): ParsedFeed {
        val user = AppJson.parseToJsonElement(
            Http.get("$API/user/$username?fields=id,username,screenname,description,avatar_360_url,url").body
        )
        val videos = AppJson.parseToJsonElement(
            Http.get(
                "$API/user/$username/videos?fields=id,title,description,thumbnail_720_url,created_time,duration,url" +
                    "&sort=recent&limit=40"
            ).body
        )
        val episodes = videos.arr("list").orEmpty().mapNotNull { v ->
            val id = v.str("id") ?: return@mapNotNull null
            ParsedEpisode(
                guid = id,
                title = v.str("title") ?: "Vídeo",
                url = v.str("url") ?: "https://www.dailymotion.com/video/$id",
                description = v.str("description").orEmpty(),
                thumbnailUrl = v.str("thumbnail_720_url"),
                publishedAt = (v.long("created_time") ?: 0) * 1000,
                durationSec = v.long("duration") ?: 0,
            )
        }
        return ParsedFeed(
            type = SourceType.DAILYMOTION,
            sourceKey = username,
            title = user.str("screenname") ?: username,
            description = user.str("description").orEmpty(),
            imageUrl = user.str("avatar_360_url"),
            siteUrl = user.str("url") ?: "https://www.dailymotion.com/$username",
            episodes = episodes,
        )
    }
}
