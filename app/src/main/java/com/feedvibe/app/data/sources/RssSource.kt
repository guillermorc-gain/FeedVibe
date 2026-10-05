package com.feedvibe.app.data.sources

import java.net.URI

/** Lector genérico de RSS 2.0 / Atom / RDF, con soporte de podcasts (enclosure, itunes). */
object RssSource {
    private val imgRegex = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val altLinkRegex = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val attrRegex = Regex("""(\w+)\s*=\s*["']([^"']*)["']""")

    suspend fun fetch(feedUrl: String, type: SourceType = SourceType.RSS): ParsedFeed {
        val resp = Http.get(feedUrl)
        return parse(resp.body, resp.finalUrl, type, sourceKey = feedUrl)
    }

    fun looksLikeFeed(body: String): Boolean {
        val head = body.take(2000).lowercase()
        return head.contains("<rss") || head.contains("<feed") || head.contains("<rdf:rdf")
    }

    /** Busca <link rel="alternate" type="application/rss+xml"> en una página HTML. */
    fun discoverFeeds(html: String, baseUrl: String): List<String> =
        altLinkRegex.findAll(html).mapNotNull { m ->
            val attrs = attrRegex.findAll(m.value).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val t = attrs["type"]?.lowercase() ?: return@mapNotNull null
            if (attrs["rel"]?.lowercase()?.contains("alternate") != true) return@mapNotNull null
            if (!t.contains("rss") && !t.contains("atom")) return@mapNotNull null
            attrs["href"]?.let { resolve(baseUrl, it) }
        }.distinct().toList()

    fun resolve(base: String, href: String): String =
        runCatching { URI(base).resolve(href.trim()).toString() }.getOrDefault(href)

    fun parse(xml: String, baseUrl: String, type: SourceType, sourceKey: String): ParsedFeed {
        val root = XmlNode.parse(xml)
        val rss = root.child("rss")
        val atom = root.child("feed")
        val rdf = root.child("rdf:RDF") ?: root.child("RDF")
        return when {
            atom != null -> parseAtom(atom, baseUrl, type, sourceKey)
            rss != null -> parseRss(rss.child("channel") ?: rss, rss.child("channel")?.all("item").orEmpty(), baseUrl, type, sourceKey)
            rdf != null -> parseRss(rdf.child("channel") ?: rdf, rdf.all("item"), baseUrl, type, sourceKey)
            else -> throw SourceException("No se reconoce el formato del feed")
        }
    }

    private fun parseRss(channel: XmlNode, items: List<XmlNode>, baseUrl: String, type: SourceType, key: String): ParsedFeed {
        val image = channel.child("itunes:image")?.attr("href")
            ?: channel.child("image")?.childText("url")
            ?: channel.child("media:thumbnail")?.attr("url")
        val hasAudio = items.any { it.child("enclosure")?.attr("type")?.startsWith("audio") == true }
        val episodes = items.mapNotNull { item ->
            val link = item.childText("link")?.let { resolve(baseUrl, it) }
            val enclosure = item.child("enclosure")
            val mediaContent = item.child("media:content") ?: item.find("media:content")
            val mcMedium = mediaContent?.attr("medium")
            val mcType = mediaContent?.attr("type")
            val mcIsAv = mcMedium == "audio" || mcMedium == "video" ||
                mcType?.startsWith("audio") == true || mcType?.startsWith("video") == true
            val mcIsImage = mcMedium == "image" || mcType?.startsWith("image") == true
            val encType = enclosure?.attr("type")
            val mediaUrl = enclosure?.attr("url")?.takeIf { encType?.startsWith("image") != true }
                ?: mediaContent?.attr("url")?.takeIf { mcIsAv }
            val guid = item.childText("guid") ?: link ?: mediaUrl ?: item.childText("title") ?: return@mapNotNull null
            val html = item.childText("content:encoded") ?: item.childText("description") ?: ""
            val thumb = item.child("itunes:image")?.attr("href")
                ?: item.find("media:thumbnail")?.attr("url")
                ?: mediaContent?.attr("url")?.takeIf { mcIsImage }
                ?: enclosure?.attr("url")?.takeIf { encType?.startsWith("image") == true }
                ?: imgRegex.find(html)?.groupValues?.get(1)
            ParsedEpisode(
                guid = guid,
                title = item.childText("title")?.let(::cleanText) ?: "(sin título)",
                url = link ?: mediaUrl ?: guid,
                description = html,
                thumbnailUrl = thumb?.let { resolve(baseUrl, it) },
                mediaUrl = mediaUrl,
                mediaType = if (mediaUrl != null) encType ?: mcType else null,
                publishedAt = Dates.parse(item.childText("pubDate") ?: item.childText("dc:date") ?: item.childText("published"))
                    ?: System.currentTimeMillis(),
                durationSec = Dates.parseDuration(item.childText("itunes:duration") ?: mediaContent?.attr("duration")),
            )
        }
        return ParsedFeed(
            type = if (type == SourceType.RSS && hasAudio) SourceType.PODCAST else type,
            sourceKey = key,
            title = channel.childText("title")?.let(::cleanText) ?: baseUrl,
            description = channel.childText("description") ?: channel.childText("itunes:summary") ?: "",
            imageUrl = image?.let { resolve(baseUrl, it) },
            siteUrl = channel.childText("link")?.let { resolve(baseUrl, it) },
            episodes = episodes,
        )
    }

    private fun atomLink(node: XmlNode, rel: String = "alternate"): String? {
        val links = node.all("link")
        return (links.firstOrNull { (it.attr("rel") ?: "alternate") == rel } ?: links.firstOrNull())?.attr("href")
    }

    private fun parseAtom(feed: XmlNode, baseUrl: String, type: SourceType, key: String): ParsedFeed {
        val episodes = feed.all("entry").mapNotNull { entry ->
            val videoId = entry.childText("yt:videoId")
            val link = atomLink(entry)?.let { resolve(baseUrl, it) }
            val guid = videoId ?: entry.childText("id") ?: link ?: return@mapNotNull null
            val group = entry.child("media:group")
            val html = group?.childText("media:description") ?: entry.childText("content") ?: entry.childText("summary") ?: ""
            val enclosure = entry.all("link").firstOrNull { it.attr("rel") == "enclosure" }
            val thumb = group?.child("media:thumbnail")?.attr("url")
                ?: entry.find("media:thumbnail")?.attr("url")
                ?: videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
                ?: imgRegex.find(html)?.groupValues?.get(1)
            ParsedEpisode(
                guid = guid,
                title = entry.childText("title")?.let(::cleanText) ?: "(sin título)",
                url = link ?: guid,
                description = html,
                thumbnailUrl = thumb?.let { resolve(baseUrl, it) },
                mediaUrl = enclosure?.attr("href"),
                mediaType = enclosure?.attr("type"),
                publishedAt = Dates.parse(entry.childText("published") ?: entry.childText("updated")) ?: System.currentTimeMillis(),
            )
        }
        return ParsedFeed(
            type = type,
            sourceKey = key,
            title = feed.childText("title")?.let(::cleanText) ?: baseUrl,
            description = feed.childText("subtitle") ?: "",
            imageUrl = (feed.childText("logo") ?: feed.childText("icon"))?.let { resolve(baseUrl, it) },
            siteUrl = atomLink(feed)?.let { resolve(baseUrl, it) },
            episodes = episodes,
        )
    }

    fun cleanText(s: String): String =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
}
