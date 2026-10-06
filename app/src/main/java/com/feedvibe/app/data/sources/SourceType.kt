package com.feedvibe.app.data.sources

enum class SourceType(val label: String, val colorHex: Long) {
    YOUTUBE("YouTube", 0xFFFF0033),
    TWITCH("Twitch", 0xFF9146FF),
    DAILYMOTION("Dailymotion", 0xFF0A0AFF),
    VIMEO("Vimeo", 0xFF17D5FF),
    ODYSEE("Odysee", 0xFFEF1970),
    PODCAST("Podcast", 0xFF8E44AD),
    RSS("RSS", 0xFFF57C00);

    companion object {
        fun fromName(name: String): SourceType = entries.firstOrNull { it.name == name } ?: RSS
    }
}

/** Resultado normalizado de leer cualquier fuente. */
data class ParsedFeed(
    val type: SourceType,
    val sourceKey: String,
    val title: String,
    val description: String = "",
    val imageUrl: String? = null,
    val siteUrl: String? = null,
    val episodes: List<ParsedEpisode>,
)

data class ParsedEpisode(
    /** Identificador único dentro de la fuente (guid, videoId...). */
    val guid: String,
    val title: String,
    val url: String,
    val description: String = "",
    val thumbnailUrl: String? = null,
    val mediaUrl: String? = null,
    val mediaType: String? = null,
    val publishedAt: Long,
    val durationSec: Long = 0,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
)

/** Error con mensaje legible para el usuario. */
class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)
