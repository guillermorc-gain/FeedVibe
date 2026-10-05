package com.feedvibe.app.data.sources

import android.net.Uri

/**
 * YouTube sin clave de API: se resuelve el channelId a partir de la URL/@handle y
 * se lee el feed público https://www.youtube.com/feeds/videos.xml.
 */
object YouTubeSource {
    private val channelIdRegex = Regex("""(UC[\w-]{22})""")
    private val pageChannelId = listOf(
        Regex(""""externalId":"(UC[\w-]{22})""""),
        Regex(""""channelId":"(UC[\w-]{22})""""),
        Regex("""<link rel="canonical" href="https://www\.youtube\.com/channel/(UC[\w-]{22})""""),
        Regex("""<meta itemprop="(?:channelId|identifier)" content="(UC[\w-]{22})""""),
    )
    private val ogImage = Regex("""<meta property="og:image" content="([^"]+)"""")
    private val ogTitle = Regex("""<meta property="og:title" content="([^"]+)"""")

    fun matches(input: String): Boolean {
        val lower = input.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be") ||
            (input.startsWith("@") && !input.contains(' ')) || Regex("^UC[\\w-]{22}$").matches(input)
    }

    /** Clave: "channel:UC..." o "playlist:PL...". */
    suspend fun resolveKey(input: String): String {
        val text = input.trim()
        if (Regex("^UC[\\w-]{22}$").matches(text)) return "channel:$text"
        if (text.startsWith("@")) return "channel:" + channelIdFromPage("https://www.youtube.com/$text")
        val uri = Uri.parse(if (text.startsWith("http")) text else "https://$text")
        uri.getQueryParameter("list")?.let { list ->
            if (uri.path.orEmpty().startsWith("/playlist")) return "playlist:$list"
        }
        val path = uri.path.orEmpty()
        Regex("^/channel/(UC[\\w-]{22})").find(path)?.let { return "channel:${it.groupValues[1]}" }
        // @handle, /c/, /user/, vídeos, shorts, youtu.be...: leer la página y buscar el channelId.
        return "channel:" + channelIdFromPage(uri.toString())
    }

    private suspend fun channelIdFromPage(url: String): String {
        val html = Http.get(url).body
        for (r in pageChannelId) r.find(html)?.let { return it.groupValues[1] }
        throw SourceException("No se ha encontrado el canal de YouTube")
    }

    fun feedUrl(key: String, hideShorts: Boolean): String {
        val (kind, id) = key.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when {
            kind == "playlist" -> "https://www.youtube.com/feeds/videos.xml?playlist_id=$id"
            // La lista "UULF" de un canal contiene solo vídeos largos (sin Shorts ni directos).
            hideShorts -> "https://www.youtube.com/feeds/videos.xml?playlist_id=UULF${id.removePrefix("UC")}"
            else -> "https://www.youtube.com/feeds/videos.xml?channel_id=$id"
        }
    }

    suspend fun fetch(key: String, hideShorts: Boolean, fetchChannelInfo: Boolean): ParsedFeed {
        val feed = try {
            RssSource.fetch(feedUrl(key, hideShorts), SourceType.YOUTUBE)
        } catch (e: SourceException) {
            if (!hideShorts) throw e
            RssSource.fetch(feedUrl(key, false), SourceType.YOUTUBE)
        }
        val id = key.substringAfter(':')
        val site = if (key.startsWith("playlist:")) "https://www.youtube.com/playlist?list=$id"
        else "https://www.youtube.com/channel/$id"
        var image: String? = null
        var title = feed.title.removePrefix("Uploads from ").removePrefix("Vídeos de ")
        if (fetchChannelInfo) {
            runCatching {
                val html = Http.get(site).body
                image = ogImage.find(html)?.groupValues?.get(1)
                ogTitle.find(html)?.groupValues?.get(1)?.let { title = RssSource.cleanText(it) }
            }
        }
        return feed.copy(
            type = SourceType.YOUTUBE,
            sourceKey = key,
            title = title,
            imageUrl = image,
            siteUrl = site,
            episodes = feed.episodes.map { ep ->
                // Los Shorts publicados aparecen con enlace /shorts/ en algunos feeds.
                ep.copy(url = ep.url.ifBlank { "https://www.youtube.com/watch?v=${ep.guid}" })
            },
        )
    }

    fun videoIdFromUrl(url: String): String? {
        val uri = Uri.parse(url)
        uri.getQueryParameter("v")?.let { return it }
        val p = uri.pathSegments
        if (uri.host?.contains("youtu.be") == true) return p.firstOrNull()
        if (p.size >= 2 && (p[0] == "shorts" || p[0] == "live" || p[0] == "embed")) return p[1]
        return null
    }
}
