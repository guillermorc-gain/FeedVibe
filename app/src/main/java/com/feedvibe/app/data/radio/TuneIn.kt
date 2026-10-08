package com.feedvibe.app.data.radio

import com.feedvibe.app.data.sources.AppJson
import com.feedvibe.app.data.sources.Http
import com.feedvibe.app.data.sources.SourceException
import com.feedvibe.app.data.sources.long
import com.feedvibe.app.data.sources.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Emisora (o programa a la carta) del catálogo. */
@Serializable
data class Station(
    val id: String,
    val name: String,
    val subtitle: String = "",
    val image: String? = null,
    val bitrate: Int = 0,
    val format: String = "",
    /** Programa a la carta (id p...) en vez de emisora en directo. */
    val isShow: Boolean = false,
)

/** Enlace de emisión con su calidad. */
data class Stream(val url: String, val bitrate: Int, val format: String) {
    val isHls get() = format.equals("hls", true) || url.contains(".m3u8", true)
}

/** Episodio a la carta de un programa (se puede descargar). */
data class Episode(val id: String, val title: String, val date: String, val durationSec: Long, val image: String?)

/** Programa de la parrilla de una emisora. */
data class ScheduleItem(val title: String, val showId: String?, val startMs: Long, val durationSec: Long, val image: String?) {
    val endMs get() = startMs + durationSec * 1000
}

/** Opción de un filtro (zona o estilo): texto y dirección para pedir su lista. */
data class Category(val title: String, val url: String)

/**
 * Catálogo público de TuneIn (el que usan muchas radios para coche y altavoces): emisoras por
 * zona y estilo, búsqueda, enlaces de emisión con su calidad, programación y programas a la carta.
 */
object TuneIn {
    private const val BASE = "https://opml.radiotime.com/"
    private const val SPAIN = "r100416"

    private suspend fun json(url: String): JsonElement {
        val sep = if ('?' in url) '&' else '?'
        val full = url.replace("http://", "https://") + "${sep}render=json&locale=es"
        val body = Http.get(full, mapOf("Accept-Language" to "es-ES")).body
        return AppJson.parseToJsonElement(body)
    }

    /** Todos los elementos (enlaces y audios) de una respuesta, aunque vengan agrupados. */
    private fun outlines(root: JsonElement): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        fun walk(e: JsonElement?) {
            when (e) {
                is JsonObject -> {
                    if (e.str("text") != null && (e.str("URL") != null || e.str("guide_id") != null)) out += e
                    (e["children"] as? JsonArray)?.forEach { walk(it) }
                }
                is JsonArray -> e.forEach { walk(it) }
                else -> Unit
            }
        }
        walk((root as? JsonObject)?.get("body"))
        return out
    }

    private fun toStation(o: JsonObject): Station? {
        val id = o.str("guide_id") ?: return null
        val isStation = o.str("item") == "station" || id.startsWith("s")
        val isShow = o.str("item") == "show" || id.startsWith("p")
        if (!isStation && !isShow) return null
        return Station(
            id = id,
            name = o.str("text").orEmpty(),
            subtitle = o.str("subtext") ?: o.str("current_track").orEmpty(),
            image = o.str("image")?.replace("http://", "https://"),
            bitrate = o.str("bitrate")?.toIntOrNull() ?: 0,
            format = o.str("formats").orEmpty(),
            isShow = isShow && !isStation,
        )
    }

    private fun stations(root: JsonElement) = outlines(root).mapNotNull(::toStation).distinctBy { it.id }

    /** Las más escuchadas en España. */
    suspend fun popular(): List<Station> = stations(json("${BASE}Browse.ashx?id=$SPAIN&filter=s:popular"))

    /** Zonas de España (ciudades y provincias que tiene el catálogo). */
    suspend fun zones(): List<Category> = outlines(json("${BASE}Browse.ashx?id=$SPAIN"))
        .filter { it.str("guide_id")?.startsWith("r") == true }
        .map { Category(it.str("text").orEmpty(), it.str("URL").orEmpty()) }

    /** Emisoras locales cerca de un sitio (TuneIn las agrupa por cercanía). */
    suspend fun local(place: Place): List<Station> =
        stations(json("${BASE}Browse.ashx?c=local&latlon=${place.lat},${place.lon}")).filter { !it.isShow }

    /** Emisoras de varios sitios juntas (p. ej. toda una comunidad), sin repetir. */
    suspend fun local(places: List<Place>): List<Station> = kotlinx.coroutines.coroutineScope {
        places.map { p -> kotlinx.coroutines.async { runCatching { local(p) }.getOrDefault(emptyList()) } }
            .flatMap { it.await() }
            .distinctBy { it.id }
    }

    /** Estilos con emisoras en España. */
    suspend fun genres(): List<Category> = outlines(json("${BASE}Browse.ashx?id=$SPAIN&pivot=genre&filter=country"))
        .filter { it.str("type") == "link" && it.str("URL") != null }
        .map { Category(it.str("text").orEmpty(), it.str("URL").orEmpty()) }

    /** Emisoras de una zona o un estilo (sigue «Emisoras» si viene agrupado). */
    suspend fun browse(url: String): List<Station> {
        val root = json(url)
        val found = stations(root).filter { !it.isShow }
        if (found.isNotEmpty()) return found
        // A veces la primera página solo trae apartados («Emisoras», «Programas»…).
        val more = outlines(root).firstOrNull { (it.str("key") == "stations" || it.str("text")?.contains("Emisora", true) == true) && it.str("URL") != null }
        return more?.let { stations(json(it.str("URL")!!)).filter { s -> !s.isShow } }.orEmpty()
    }

    suspend fun search(query: String): List<Station> =
        stations(json("${BASE}Search.ashx?query=" + URLEncoder.encode(query, "UTF-8")))

    /** Enlaces de emisión de una emisora (puede haber varias calidades). Las listas .pls/.m3u se abren. */
    suspend fun streams(id: String): List<Stream> {
        val root = json("${BASE}Tune.ashx?id=$id&formats=mp3,aac,ogg,hls")
        val list = ((root as? JsonObject)?.get("body") as? JsonArray).orEmpty().mapNotNull { e ->
            val url = e.str("url") ?: return@mapNotNull null
            Stream(url, e.long("bitrate")?.toInt() ?: 0, e.str("media_type").orEmpty())
        }.mapNotNull { s -> runCatching { resolvePlaylist(s) }.getOrNull() }
        if (list.isEmpty()) throw SourceException("Esta emisora no tiene ninguna emisión disponible ahora")
        return list
    }

    /** Las listas de reproducción (.pls, .m3u) llevan dentro el enlace de verdad. */
    private suspend fun resolvePlaylist(s: Stream): Stream {
        val path = s.url.substringBefore('?').lowercase()
        if (!path.endsWith(".pls") && !path.endsWith(".m3u")) return s
        val body = Http.get(s.url).body
        val url = body.lineSequence().map { it.trim().substringAfter('=', it.trim()) }
            .firstOrNull { it.startsWith("http") } ?: throw SourceException("Lista sin emisiones")
        return s.copy(url = url)
    }

    /** Episodios recientes de un programa a la carta. */
    suspend fun episodes(showId: String): List<Episode> = outlines(json("${BASE}Tune.ashx?c=pbrowse&id=$showId"))
        .filter { it.str("guide_id")?.startsWith("t") == true }
        .map { o ->
            Episode(
                id = o.str("guide_id")!!,
                title = o.str("text").orEmpty(),
                date = o.str("subtext").orEmpty(),
                durationSec = o.str("topic_duration")?.toLongOrNull() ?: 0,
                image = o.str("image")?.replace("http://", "https://"),
            )
        }

    /** Programación de hoy (y mañana si la hay). */
    suspend fun schedule(id: String): List<ScheduleItem> = outlines(json("${BASE}Browse.ashx?c=schedule&id=$id")).mapNotNull { o ->
        val start = o.str("start_utc") ?: return@mapNotNull null
        val ms = runCatching { LocalDateTime.parse(start).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
        ScheduleItem(o.str("text").orEmpty(), o.str("guide_id"), ms, o.long("duration") ?: 0, o.str("image")?.replace("http://", "https://"))
    }.sortedBy { it.startMs }
}
