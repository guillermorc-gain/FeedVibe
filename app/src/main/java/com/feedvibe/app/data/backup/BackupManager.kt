package com.feedvibe.app.data.backup

import com.feedvibe.app.data.db.EpisodeStateEntity
import com.feedvibe.app.data.prefs.AppSettings
import com.feedvibe.app.data.prefs.SettingsRepository
import com.feedvibe.app.data.repo.FeedRepository
import com.feedvibe.app.data.sources.AppJson
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
                BackupSubscription(it.type.name, it.sourceKey, it.title, it.imageUrl, it.siteUrl, it.category, it.notify)
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
            repo.importSubscription(SourceType.fromName(it.type), it.sourceKey, it.title, it.imageUrl, it.siteUrl, it.category, it.notify)
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

    suspend fun importOpml(xml: String): Int {
        val root = XmlNode.parse(xml)
        val outlines = mutableListOf<Pair<XmlNode, String?>>()
        fun walk(n: XmlNode, category: String?) {
            n.all("outline").forEach { o ->
                if (o.attr("xmlUrl") != null || o.attr("feedvibeKey") != null) outlines += o to (o.attr("category") ?: category)
                else walk(o, o.attr("text") ?: o.attr("title"))
            }
        }
        root.find("body")?.let { walk(it, null) }
        var count = 0
        for ((o, cat) in outlines) {
            val fvType = o.attr("feedvibeType")
            val fvKey = o.attr("feedvibeKey")
            val title = o.attr("title") ?: o.attr("text") ?: continue
            val (type, key) = if (fvType != null && fvKey != null) {
                SourceType.fromName(fvType) to fvKey
            } else {
                val url = o.attr("xmlUrl") ?: continue
                val yt = Regex("channel_id=(UC[\\w-]{22})").find(url)?.groupValues?.get(1)
                if (yt != null) SourceType.YOUTUBE to "channel:$yt" else SourceType.RSS to url
            }
            repo.importSubscription(type, key, title, null, o.attr("htmlUrl"), cat, true)
            count++
        }
        return count
    }
}
