package com.feedvibe.app.data.sources

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Plan B sin clave de API: lee la pestaña «Vídeos» del canal (o la página de la lista)
 * y extrae los vídeos del JSON `ytInitialData` que lleva la propia página.
 * Se usa cuando el feed RSS de YouTube falla (devuelve 404/500 con frecuencia).
 */
object YouTubePage {
    private val relativeEn = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE)
    private val relativeEs = Regex("""hace\s+(\d+)\s+(segundo|minuto|hora|día|dia|semana|mes|año|ano)""", RegexOption.IGNORE_CASE)
    private val durationText = Regex("""^\d{1,2}(:\d{2}){1,2}$""")

    private val ENGLISH = mapOf("Accept-Language" to "en-US,en;q=0.9")

    suspend fun fetch(key: String): ParsedFeed = fetchPages(key, limit = 0) {}

    /**
     * Todos los vídeos del canal sin clave de API: la página «Vídeos» carga más al hacer
     * scroll mediante "continuaciones"; aquí se piden una tras otra hasta llegar al primero.
     */
    suspend fun fetchAll(key: String, limit: Int = 5000, onProgress: (Int) -> Unit): List<ParsedEpisode> =
        fetchPages(key, limit, onProgress).episodes

    private suspend fun fetchPages(key: String, limit: Int, onProgress: (Int) -> Unit): ParsedFeed {
        val (kind, id) = key.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        // hl=en: fechas relativas en inglés ("2 days ago"), fáciles de interpretar.
        val url = if (kind == "playlist") "https://www.youtube.com/playlist?list=$id&hl=en&gl=US"
        else "https://www.youtube.com/channel/$id/videos?hl=en&gl=US"
        val html = Http.get(url, ENGLISH).body
        val data = extractInitialData(html) ?: throw SourceException("No se pudo leer la página del canal de YouTube")

        val renderers = mutableListOf<Pair<String, JsonObject>>()
        collect(data, renderers)
        var token = lastContinuation(data)
        if (limit > 0) {
            onProgress(renderers.size)
            val clientVersion = Regex(""""INNERTUBE_CLIENT_VERSION":"([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: "2.20250101.00.00"
            var pages = 0
            while (renderers.size < limit && pages < 300) {
                val t = token ?: break
                val body = buildJsonObject {
                    putJsonObject("context") {
                        putJsonObject("client") {
                            put("clientName", "WEB")
                            put("clientVersion", clientVersion)
                            put("hl", "en")
                            put("gl", "US")
                        }
                    }
                    put("continuation", t)
                }.toString()
                val page = AppJson.parseToJsonElement(
                    Http.postJson("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", body, ENGLISH)
                )
                val before = renderers.size
                collect(page, renderers)
                token = lastContinuation(page)
                pages++
                onProgress(renderers.size)
                if (renderers.size == before) break
            }
        }
        val now = System.currentTimeMillis()
        val seen = HashSet<String>()
        val episodes = renderers.mapIndexedNotNull { index, (type, r) ->
            val ep = if (type == "lockupViewModel") parseLockup(r) else parseRenderer(r)
            ep ?: return@mapIndexedNotNull null
            if (!seen.add(ep.first)) return@mapIndexedNotNull null
            val (videoId, title, extra) = ep
            ParsedEpisode(
                guid = videoId, // igual que en el RSS y la API: no se duplican episodios
                title = title,
                url = "https://www.youtube.com/watch?v=$videoId",
                thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                // Fecha aproximada a partir de "hace X días"; se resta el índice para conservar el orden.
                publishedAt = (extra.published ?: now) - index * 1000L,
                durationSec = extra.durationSec,
                isLive = extra.live,
            )
        }

        val meta = data.obj("metadata").obj("channelMetadataRenderer")
        val title = meta.str("title")
            ?: Regex("""<meta property="og:title" content="([^"]+)"""").find(html)?.groupValues?.get(1)?.let(RssSource::cleanText)
            ?: "YouTube"
        val avatar = meta.obj("avatar").arr("thumbnails")?.lastOrNull().str("url")
            ?: Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
        return ParsedFeed(
            type = SourceType.YOUTUBE,
            sourceKey = key,
            title = title,
            description = meta.str("description").orEmpty(),
            imageUrl = avatar,
            siteUrl = if (kind == "playlist") "https://www.youtube.com/playlist?list=$id" else "https://www.youtube.com/channel/$id",
            episodes = episodes,
        )
    }

    /** Token para pedir la siguiente tanda de vídeos (el último que aparece en la respuesta). */
    private fun lastContinuation(el: JsonElement): String? {
        var found: String? = null
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    e["continuationItemRenderer"]?.let { c ->
                        c.obj("continuationEndpoint").obj("continuationCommand").str("token")?.let { found = it }
                    }
                    e.values.forEach(::walk)
                }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(el)
        return found
    }

    private fun extractInitialData(html: String): JsonElement? {
        val marker = listOf("var ytInitialData = ", "window[\"ytInitialData\"] = ", "ytInitialData = ")
            .firstNotNullOfOrNull { m -> html.indexOf(m).takeIf { it >= 0 }?.let { it + m.length } } ?: return null
        val end = html.indexOf(";</script>", marker).takeIf { it > marker } ?: return null
        return runCatching { AppJson.parseToJsonElement(html.substring(marker, end)) }.getOrNull()
    }

    private val rendererKeys = setOf("videoRenderer", "gridVideoRenderer", "playlistVideoRenderer", "lockupViewModel")

    private fun collect(el: JsonElement, out: MutableList<Pair<String, JsonObject>>) {
        when (el) {
            is JsonObject -> for ((k, v) in el) {
                if (k in rendererKeys && v is JsonObject) out += k to v else collect(v, out)
            }
            is JsonArray -> el.forEach { collect(it, out) }
            else -> Unit
        }
    }

    private class Extra(val published: Long?, val durationSec: Long, val live: Boolean)

    private fun text(el: JsonElement?): String? =
        el.str("simpleText") ?: el.arr("runs")?.joinToString("") { it.str("text").orEmpty() }?.takeIf { it.isNotBlank() }

    private fun parseRenderer(r: JsonObject): Triple<String, String, Extra>? {
        val id = r.str("videoId") ?: return null
        val title = text(r["title"]) ?: return null
        val strings = allStrings(r)
        val published = strings.firstNotNullOfOrNull(::parseRelative)
        val duration = r.str("lengthSeconds")?.toLongOrNull()
            ?: text(r["lengthText"])?.let { Dates.parseDuration(it) } ?: 0
        val live = strings.any { it.equals("LIVE", true) || it.contains("watching", true) }
        return Triple(id, title, Extra(published, duration, live))
    }

    private fun parseLockup(r: JsonObject): Triple<String, String, Extra>? {
        if (r.str("contentType")?.contains("VIDEO") == false) return null
        val id = r.str("contentId") ?: return null
        val title = r.obj("metadata").obj("lockupMetadataViewModel").obj("title").str("content") ?: return null
        val strings = allStrings(r)
        val published = strings.firstNotNullOfOrNull(::parseRelative)
        val duration = strings.firstOrNull { durationText.matches(it.trim()) }?.let { Dates.parseDuration(it) } ?: 0
        val live = strings.any { it.equals("LIVE", true) || it.contains("watching", true) }
        return Triple(id, title, Extra(published, duration, live))
    }

    private fun allStrings(el: JsonElement, out: MutableList<String> = mutableListOf()): List<String> {
        when (el) {
            is JsonObject -> el.values.forEach { allStrings(it, out) }
            is JsonArray -> el.forEach { allStrings(it, out) }
            is JsonPrimitive -> if (el.isString) el.contentOrNull?.let { if (it.length < 60) out += it }
        }
        return out
    }

    private fun parseRelative(s: String): Long? {
        val en = relativeEn.find(s)
        val es = if (en == null) relativeEs.find(s) else null
        val m = en ?: es ?: return null
        val n = m.groupValues[1].toLong()
        val unit = when (m.groupValues[2].lowercase()) {
            "second", "segundo" -> 1_000L
            "minute", "minuto" -> 60_000L
            "hour", "hora" -> 3_600_000L
            "day", "día", "dia" -> 86_400_000L
            "week", "semana" -> 7 * 86_400_000L
            "month", "mes" -> 30 * 86_400_000L
            else -> 365 * 86_400_000L
        }
        return System.currentTimeMillis() - n * unit
    }
}
