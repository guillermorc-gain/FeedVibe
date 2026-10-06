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
 * Lectura de YouTube sin clave de API, a partir del JSON `ytInitialData` de la propia web y
 * de las "continuaciones" que la web pide al hacer scroll.
 *
 * Comprobado contra YouTube (oct. 2026): la lista de subidas UU… de un canal de 1.311 vídeos
 * se recorre entera; las páginas en español dan los títulos originales y fechas como
 * «hace 9 horas» o «Emitido hace 5 años»; la lista UUSH… contiene solo los Shorts.
 */
object YouTubePage {
    private val relativeEn = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE)
    private val relativeEs = Regex("""hace\s+(\d+)\s+(segundo|minuto|hora|día|dia|semana|mes|año|ano)""", RegexOption.IGNORE_CASE)
    private val durationText = Regex("""^\d{1,2}(:\d{2}){1,2}$""")
    private val SPANISH = mapOf("Accept-Language" to "es-ES,es;q=0.9")
    private const val BROWSE = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false"

    /** Primera página (unos 30 vídeos) de la pestaña «Vídeos»: plan B cuando falla el RSS. */
    suspend fun fetch(key: String): ParsedFeed {
        val (kind, id) = split(key)
        val url = if (kind == "playlist") "https://www.youtube.com/playlist?list=$id&hl=es&gl=ES"
        else "https://www.youtube.com/channel/$id/videos?hl=es&gl=ES"
        val page = paginate(url, maxItems = 0) {}
        return ParsedFeed(
            type = SourceType.YOUTUBE,
            sourceKey = key,
            title = page.title ?: "YouTube",
            description = page.description,
            imageUrl = page.avatar,
            siteUrl = if (kind == "playlist") "https://www.youtube.com/playlist?list=$id" else "https://www.youtube.com/channel/$id",
            episodes = toEpisodes(page.items, emptySet()),
        )
    }

    /**
     * Todos los vídeos del canal: la lista de subidas completa (vídeos, directos y Shorts),
     * marcando como Short los que aparecen en la lista UUSH….
     */
    suspend fun fetchAll(key: String, limit: Int = 5000, onProgress: (Int) -> Unit): List<ParsedEpisode> {
        val (kind, id) = split(key)
        if (kind == "playlist") {
            val page = paginate("https://www.youtube.com/playlist?list=$id&hl=es&gl=ES", limit, onProgress)
            return toEpisodes(page.items, emptySet())
        }
        val base = id.removePrefix("UC")
        val uploads = runCatching {
            paginate("https://www.youtube.com/playlist?list=UU$base&hl=es&gl=ES", limit, onProgress)
        }.getOrNull()?.takeIf { it.items.isNotEmpty() }
            // Si la lista de subidas no se puede leer, al menos la pestaña «Vídeos».
            ?: paginate("https://www.youtube.com/channel/$id/videos?hl=es&gl=ES", limit, onProgress)
        val shortIds = runCatching {
            paginate("https://www.youtube.com/playlist?list=UUSH$base&hl=es&gl=ES", limit) {}.shortIds
        }.getOrDefault(emptySet())
        return toEpisodes(uploads.items, shortIds)
    }

    private fun split(key: String) = key.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }

    // ------------------------------------------------------------------ paginación

    private class Page(
        val items: List<Pair<String, JsonObject>>,
        val shortIds: Set<String>,
        val title: String?,
        val description: String,
        val avatar: String?,
    )

    /** Descarga la página y sigue pidiendo continuaciones hasta el final (o [maxItems]; 0 = solo la primera). */
    private suspend fun paginate(url: String, maxItems: Int, onProgress: (Int) -> Unit): Page {
        val html = Http.get(url, SPANISH).body
        val data = extractInitialData(html) ?: throw SourceException("No se pudo leer la página de YouTube")
        val items = mutableListOf<Pair<String, JsonObject>>()
        val shorts = linkedSetOf<String>()
        collect(data, items, shorts)
        var token = lastContinuation(data)
        if (maxItems > 0) {
            onProgress(items.size)
            val clientVersion = Regex(""""INNERTUBE_CLIENT_VERSION":"([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: "2.20250101.00.00"
            val visitorData = Regex(""""VISITOR_DATA":"([^"]+)"""").find(html)?.groupValues?.get(1)
            var pages = 0
            while (items.size + shorts.size < maxItems && pages < 300) {
                val t = token ?: break
                val body = buildJsonObject {
                    putJsonObject("context") {
                        putJsonObject("client") {
                            put("clientName", "WEB")
                            put("clientVersion", clientVersion)
                            put("hl", "es")
                            put("gl", "ES")
                            if (visitorData != null) put("visitorData", visitorData)
                        }
                    }
                    put("continuation", t)
                }.toString()
                // Si una página falla se reintenta una vez; si vuelve a fallar nos quedamos con lo cargado.
                val page = runCatching {
                    AppJson.parseToJsonElement(Http.postJson(BROWSE, body, SPANISH))
                }.recoverCatching {
                    kotlinx.coroutines.delay(1500)
                    AppJson.parseToJsonElement(Http.postJson(BROWSE, body, SPANISH))
                }.getOrNull() ?: break
                val before = items.size + shorts.size
                collect(page, items, shorts)
                token = lastContinuation(page)
                pages++
                onProgress(items.size)
                if (items.size + shorts.size == before) break
            }
        }
        val meta = data.obj("metadata").obj("channelMetadataRenderer")
        return Page(
            items = items,
            shortIds = shorts,
            title = meta.str("title")
                ?: Regex("""<meta property="og:title" content="([^"]+)"""").find(html)?.groupValues?.get(1)?.let(RssSource::cleanText),
            description = meta.str("description").orEmpty(),
            avatar = meta.obj("avatar").arr("thumbnails")?.lastOrNull().str("url")
                ?: Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1),
        )
    }

    private fun toEpisodes(items: List<Pair<String, JsonObject>>, shortIds: Set<String>): List<ParsedEpisode> {
        val now = System.currentTimeMillis()
        val seen = HashSet<String>()
        return items.mapIndexedNotNull { index, (type, r) ->
            val parsed = (if (type == "lockupViewModel") parseLockup(r) else parseRenderer(r)) ?: return@mapIndexedNotNull null
            val (videoId, title, extra) = parsed
            if (!seen.add(videoId)) return@mapIndexedNotNull null
            ParsedEpisode(
                guid = videoId, // igual que en el RSS y la API: no se duplican episodios
                title = title,
                url = "https://www.youtube.com/watch?v=$videoId",
                thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                // Fecha aproximada («hace 3 días»); se resta el índice para conservar el orden.
                publishedAt = (extra.published ?: now) - index * 1000L,
                durationSec = extra.durationSec,
                isLive = extra.live,
                isShort = videoId in shortIds,
            )
        }
    }

    // ------------------------------------------------------------------ JSON

    private fun extractInitialData(html: String): JsonElement? {
        val marker = listOf("var ytInitialData = ", "window[\"ytInitialData\"] = ", "ytInitialData = ")
            .firstNotNullOfOrNull { m -> html.indexOf(m).takeIf { it >= 0 }?.let { it + m.length } } ?: return null
        val end = html.indexOf(";</script>", marker).takeIf { it > marker } ?: return null
        return runCatching { AppJson.parseToJsonElement(html.substring(marker, end)) }.getOrNull()
    }

    private val rendererKeys = setOf("videoRenderer", "gridVideoRenderer", "playlistVideoRenderer", "lockupViewModel")

    /** Partes de la página que no son la lista de vídeos (sus tokens cargarían otra cosa). */
    private val SKIP_KEYS = setOf("engagementPanels", "header", "frameworkUpdates", "topbar", "sidebar")

    private fun collect(el: JsonElement, out: MutableList<Pair<String, JsonObject>>, shorts: MutableSet<String>) {
        when (el) {
            is JsonObject -> for ((k, v) in el) {
                when {
                    k in SKIP_KEYS -> Unit
                    k in rendererKeys && v is JsonObject -> out += k to v
                    (k == "shortsLockupViewModel" || k == "reelItemRenderer") && v is JsonObject ->
                        findString(v, "videoId")?.let { shorts += it }
                    else -> collect(v, out, shorts)
                }
            }
            is JsonArray -> el.forEach { collect(it, out, shorts) }
            else -> Unit
        }
    }

    private fun findString(el: JsonElement, key: String): String? {
        when (el) {
            is JsonObject -> {
                (el[key] as? JsonPrimitive)?.contentOrNull?.let { return it }
                for (v in el.values) findString(v, key)?.let { return it }
            }
            is JsonArray -> for (v in el) findString(v, key)?.let { return it }
            else -> Unit
        }
        return null
    }

    /**
     * Token para pedir la siguiente tanda: el último dentro de un continuationItemRenderer o
     * continuationItemViewModel (formato actual de las listas), sin paneles laterales.
     */
    private fun lastContinuation(el: JsonElement): String? {
        var found: String? = null
        fun tokenInside(e: JsonElement): String? {
            when (e) {
                is JsonObject -> {
                    e.obj("continuationCommand").str("token")?.let { return it }
                    for (v in e.values) tokenInside(v)?.let { return it }
                }
                is JsonArray -> for (v in e) tokenInside(v)?.let { return it }
                else -> Unit
            }
            return null
        }
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> for ((k, v) in e) {
                    when (k) {
                        in SKIP_KEYS -> Unit
                        "continuationItemRenderer", "continuationItemViewModel" -> tokenInside(v)?.let { found = it }
                        else -> walk(v)
                    }
                }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(el)
        return found
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
        return Triple(id, title, Extra(published, duration, isLive(strings)))
    }

    private fun parseLockup(r: JsonObject): Triple<String, String, Extra>? {
        if (r.str("contentType")?.contains("VIDEO") == false) return null
        val id = r.str("contentId") ?: return null
        val title = r.obj("metadata").obj("lockupMetadataViewModel").obj("title").str("content") ?: return null
        val strings = allStrings(r)
        val published = strings.firstNotNullOfOrNull(::parseRelative)
        val duration = strings.firstOrNull { durationText.matches(it.trim()) }?.let { Dates.parseDuration(it) } ?: 0
        return Triple(id, title, Extra(published, duration, isLive(strings)))
    }

    private fun isLive(strings: List<String>) = strings.any {
        it.equals("LIVE", true) || it.equals("EN DIRECTO", true) || it.contains("watching", true) || it.contains("espectadores", true)
    }

    private fun allStrings(el: JsonElement, out: MutableList<String> = mutableListOf()): List<String> {
        when (el) {
            is JsonObject -> el.values.forEach { allStrings(it, out) }
            is JsonArray -> el.forEach { allStrings(it, out) }
            is JsonPrimitive -> if (el.isString) el.contentOrNull?.let { if (it.length < 70) out += it }
        }
        return out
    }

    private fun parseRelative(s: String): Long? {
        val m = relativeEs.find(s) ?: relativeEn.find(s) ?: return null
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
