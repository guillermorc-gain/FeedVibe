package com.feedvibe.app.data.sources

import android.net.Uri

/** Búsqueda de podcasts mediante la API pública de iTunes (sin clave). */
object PodcastSearch {
    data class Result(val title: String, val author: String, val feedUrl: String, val imageUrl: String?)

    suspend fun search(term: String): List<Result> {
        val url = "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=30&term=" + Uri.encode(term)
        val json = AppJson.parseToJsonElement(Http.get(url).body)
        return json.arr("results").orEmpty().mapNotNull { r ->
            Result(
                title = r.str("collectionName") ?: return@mapNotNull null,
                author = r.str("artistName").orEmpty(),
                feedUrl = r.str("feedUrl") ?: return@mapNotNull null,
                imageUrl = r.str("artworkUrl600") ?: r.str("artworkUrl100"),
            )
        }
    }

    /** podcasts.apple.com/.../id123456 -> URL del feed RSS. */
    suspend fun feedFromApple(url: String): String {
        val id = Regex("id(\\d+)").find(url)?.groupValues?.get(1) ?: throw SourceException("Enlace de Apple Podcasts no válido")
        val json = AppJson.parseToJsonElement(Http.get("https://itunes.apple.com/lookup?id=$id").body)
        return json.arr("results")?.firstOrNull().str("feedUrl") ?: throw SourceException("Este podcast no tiene feed público")
    }
}
