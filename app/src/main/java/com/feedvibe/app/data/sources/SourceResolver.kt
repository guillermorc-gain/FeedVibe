package com.feedvibe.app.data.sources

import android.net.Uri

/**
 * Convierte lo que escribe/pega el usuario (URL, @handle, dirección de una web...)
 * en una fuente concreta y sabe descargar cada tipo de fuente.
 */
object SourceResolver {

    data class Resolved(val type: SourceType, val key: String)

    suspend fun resolve(rawInput: String): Resolved {
        var input = rawInput.trim()
            .replace(Regex("^(feed|rss)://", RegexOption.IGNORE_CASE), "https://")
        // Al compartir desde otras apps suele venir texto + URL: nos quedamos con la URL.
        Regex("""https?://\S+""").find(input)?.let { input = it.value }
        if (input.isEmpty()) throw SourceException("Escribe una URL o un nombre de canal")
        val lower = input.lowercase()
        return when {
            YouTubeSource.matches(input) -> Resolved(SourceType.YOUTUBE, YouTubeSource.resolveKey(input))
            TwitchSource.matches(input) -> Resolved(SourceType.TWITCH, TwitchSource.resolveKey(input))
            DailymotionSource.matches(input) -> Resolved(SourceType.DAILYMOTION, DailymotionSource.resolveKey(input))
            lower.contains("vimeo.com") -> Resolved(SourceType.VIMEO, vimeoFeed(input))
            lower.contains("odysee.com") -> Resolved(SourceType.ODYSEE, odyseeFeed(input))
            lower.contains("podcasts.apple.com") -> Resolved(SourceType.PODCAST, PodcastSearch.feedFromApple(input))
            else -> Resolved(SourceType.RSS, discoverRss(input))
        }
    }

    suspend fun fetch(type: SourceType, key: String, hideShorts: Boolean = false, full: Boolean = true): ParsedFeed =
        when (type) {
            SourceType.YOUTUBE -> YouTubeSource.fetch(key, hideShorts, fetchChannelInfo = full)
            SourceType.TWITCH -> TwitchSource.fetch(key)
            SourceType.DAILYMOTION -> DailymotionSource.fetch(key)
            SourceType.VIMEO, SourceType.ODYSEE -> RssSource.fetch(key, type).copy(type = type)
            SourceType.PODCAST, SourceType.RSS -> RssSource.fetch(key, type)
        }

    private fun vimeoFeed(input: String): String {
        val uri = Uri.parse(if (input.startsWith("http")) input else "https://$input")
        val seg = uri.pathSegments.filter { it.isNotBlank() }
        return when {
            seg.firstOrNull() == "channels" && seg.size >= 2 -> "https://vimeo.com/channels/${seg[1]}/videos/rss"
            seg.isNotEmpty() -> "https://vimeo.com/${seg[0]}/videos/rss"
            else -> throw SourceException("URL de Vimeo no válida")
        }
    }

    private fun odyseeFeed(input: String): String {
        val channel = Regex("""@[^/:?#]+(?::[0-9a-f]+)?""").find(input)?.value
            ?: throw SourceException("URL de Odysee no válida")
        return "https://odysee.com/\$/rss/$channel"
    }

    /** Si es una web normal, busca su feed RSS/Atom. */
    private suspend fun discoverRss(input: String): String {
        val url = if (input.startsWith("http")) input else "https://$input"
        val resp = Http.get(url)
        if (RssSource.looksLikeFeed(resp.body)) return resp.finalUrl
        RssSource.discoverFeeds(resp.body, resp.finalUrl).firstOrNull()?.let { return it }
        for (path in listOf("/feed", "/rss", "/feed.xml", "/rss.xml", "/atom.xml", "/index.xml")) {
            val candidate = RssSource.resolve(resp.finalUrl, path)
            val ok = runCatching { RssSource.looksLikeFeed(Http.get(candidate).body) }.getOrDefault(false)
            if (ok) return candidate
        }
        throw SourceException("No se ha encontrado ningún feed RSS en esa página")
    }
}
