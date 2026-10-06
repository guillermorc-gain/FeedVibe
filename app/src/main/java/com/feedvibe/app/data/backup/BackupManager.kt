package com.feedvibe.app.data.backup

import com.feedvibe.app.data.db.EpisodeStateEntity
import com.feedvibe.app.data.prefs.AppSettings
import com.feedvibe.app.data.prefs.SettingsRepository
import com.feedvibe.app.data.repo.FeedRepository
import com.feedvibe.app.data.sources.AppJson
import com.feedvibe.app.data.sources.SourceResolver
import com.feedvibe.app.data.sources.SourceType
import com.feedvibe.app.data.sources.XmlNode
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class BackupSubscription(
    val type: String,
    val sourceKey: String,
    val title: String,
    val imageUrl: String? = null,
    val siteUrl: String? = null,
    val category: String? = null,
    val notify: Boolean = true,
    val fullHistory: Boolean = false,
)

@Serializable
data class BackupState(
    val episodeId: String,
    val subscriptionId: String? = null,
    val watched: Boolean = false,
    val watchLater: Boolean = false,
    val favorite: Boolean = false,
    val positionMs: Long = 0,
    val watchedAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class BackupFile(
    val app: String = "FeedVibe",
    val version: Int = 1,
    val createdAt: Long,
    val subscriptions: List<BackupSubscription>,
    val states: List<BackupState>,
    val settings: AppSettings? = null,
)

/** Crea/restaura copias (JSON) y exporta/importa OPML. */
class BackupManager(
    private val repo: FeedRepository,
    private val settings: SettingsRepository,
) {
    suspend fun createJson(): String {
        val file = BackupFile(
            createdAt = System.currentTimeMillis(),
            subscriptions = repo.allSubscriptions().map {
                BackupSubscription(it.type.name, it.sourceKey, it.title, it.imageUrl, it.siteUrl, it.category, it.notify, it.fullHistory)
            },
            states = repo.allStates().map {
                BackupState(it.episodeId, it.subscriptionId, it.watched, it.watchLater, it.favorite, it.positionMs, it.watchedAt, it.updatedAt)
            },
            settings = settings.current(),
        )
        return AppJson.encodeToString(BackupFile.serializer(), file)
    }

    data class RestoreSummary(val subscriptions: Int, val states: Int)

    suspend fun restoreJson(json: String, restoreSettings: Boolean = true): RestoreSummary {
        val file = AppJson.decodeFromString(BackupFile.serializer(), json)
        file.subscriptions.forEach {
            repo.importSubscription(SourceType.fromName(it.type), it.sourceKey, it.title, it.imageUrl, it.siteUrl, it.category, it.notify, it.fullHistory)
        }
        repo.restoreStates(file.states.map {
            EpisodeStateEntity(it.episodeId, it.subscriptionId, it.watched, it.watchLater, it.favorite, it.positionMs, it.watchedAt, it.updatedAt)
        })
        if (restoreSettings) file.settings?.let { s -> settings.update { s } }
        return RestoreSummary(file.subscriptions.size, file.states.size)
    }

    fun fileName(): String =
        "feedvibe-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"

    // ---------- OPML ----------

    suspend fun exportOpml(): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        val subs = repo.allSubscriptions()
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<opml version="2.0"><head><title>FeedVibe</title></head><body>""").append('\n')
        subs.forEach { s ->
            sb.append("""  <outline type="rss" text="${esc(s.title)}" title="${esc(s.title)}" xmlUrl="${esc(feedUrlFor(s.type, s.sourceKey))}"""")
            s.siteUrl?.let { sb.append(""" htmlUrl="${esc(it)}"""") }
            s.category?.let { sb.append(""" category="${esc(it)}"""") }
            sb.append(""" feedvibeType="${s.type.name}" feedvibeKey="${esc(s.sourceKey)}"/>""").append('\n')
        }
        sb.append("</body></opml>\n")
        return sb.toString()
    }

    /** URL de feed equivalente para otras apps (cuando existe). */
    private fun feedUrlFor(type: SourceType, key: String): String = when (type) {
        SourceType.YOUTUBE -> com.feedvibe.app.data.sources.YouTubeSource.feedUrl(key, false)
        SourceType.TWITCH -> "https://www.twitch.tv/$key"
        SourceType.DAILYMOTION -> "https://www.dailymotion.com/$key"
        else -> key
    }

    data class OpmlEntry(val title: String, val url: String?, val category: String?, val fvType: String?, val fvKey: String?, val htmlUrl: String?)

    data class ImportResult(val added: List<String>, val alreadyHad: Int, val failed: List<String>)

    /** Lee las entradas de un OPML (Podcast Addict, AntennaPod, Feedly, FeedVibe…). */
    fun parseOpml(xml: String): List<OpmlEntry> {
        val root = XmlNode.parse(xml)
        val out = mutableListOf<OpmlEntry>()
        fun walk(n: XmlNode, category: String?) {
            n.all("outline").forEach { o ->
                val url = o.attr("xmlUrl") ?: o.attr("xmlurl") ?: o.attr("url")
                if (url != null || o.attr("feedvibeKey") != null) {
                    out += OpmlEntry(
                        title = o.attr("title") ?: o.attr("text") ?: url ?: "",
                        url = url,
                        category = o.attr("category")?.takeIf { it.isNotBlank() } ?: category,
                        fvType = o.attr("feedvibeType"),
                        fvKey = o.attr("feedvibeKey"),
                        htmlUrl = o.attr("htmlUrl"),
                    )
                } else {
                    walk(o, o.attr("text") ?: o.attr("title"))
                }
            }
        }
        (root.find("body") ?: root).let { walk(it, null) }
        return out.distinctBy { it.fvKey ?: it.url }
    }

    /** Convierte la URL de un OPML en el tipo de fuente y clave que usa FeedVibe (sin red si es posible). */
    private suspend fun toSource(e: OpmlEntry): Pair<SourceType, String> {
        if (e.fvType != null && e.fvKey != null) return SourceType.fromName(e.fvType) to e.fvKey
        val url = e.url?.trim() ?: error("sin URL")
        val lower = url.lowercase()
        Regex("channel_id=(UC[\\w-]{22})").find(url)?.let { return SourceType.YOUTUBE to "channel:${it.groupValues[1]}" }
        Regex("playlist_id=([\\w-]+)").find(url)?.let {
            val pl = it.groupValues[1]
            // La lista de subidas UU… de un canal se guarda como el propio canal.
            return SourceType.YOUTUBE to (if (pl.startsWith("UU") && pl.length == 24) "channel:UC${pl.drop(2)}" else "playlist:$pl")
        }
        Regex("youtube\\.com/channel/(UC[\\w-]{22})").find(url)?.let { return SourceType.YOUTUBE to "channel:${it.groupValues[1]}" }
        Regex("videos\\.xml\\?user=([\\w.-]+)").find(url)?.let {
            val r = SourceResolver.resolve("https://www.youtube.com/user/${it.groupValues[1]}")
            return r.type to r.key
        }
        val isPlatform = listOf("youtube.com", "youtu.be", "twitch.tv", "dailymotion.com", "vimeo.com", "odysee.com", "podcasts.apple.com")
            .any { lower.contains(it) } && !lower.contains("/rss") && !lower.contains("/feeds/")
        if (isPlatform) {
            val r = SourceResolver.resolve(url)
            return r.type to r.key
        }
        return SourceType.PODCAST to url
    }

    /**
     * Importa todas las suscripciones de un OPML de golpe.
     * @param onProgress (hechos, total)
     */
    suspend fun importOpml(xml: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): ImportResult {
        val entries = parseOpml(xml)
        if (entries.isEmpty()) throw IllegalArgumentException("El archivo no contiene canales")
        val added = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var already = 0
        entries.forEachIndexed { i, e ->
            runCatching {
                val (type, key) = toSource(e)
                val id = repo.importSubscription(type, key, e.title, null, e.htmlUrl, e.category, true, false)
                if (id != null) added += id else already++
            }.onFailure { failed += e.title }
            onProgress(i + 1, entries.size)
        }
        return ImportResult(added, already, failed)
    }
}
