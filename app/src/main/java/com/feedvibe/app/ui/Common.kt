package com.feedvibe.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.staticCompositionLocalOf
import com.feedvibe.app.AppContainer
import com.feedvibe.app.data.db.EpisodeItem
import com.feedvibe.app.data.prefs.OpenMode
import com.feedvibe.app.data.sources.SourceType
import com.feedvibe.app.data.sources.YouTubeSource
import kotlinx.coroutines.launch

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("Sin AppContainer") }

fun relativeTime(time: Long): String {
    if (time <= 0) return ""
    return DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}

fun formatDuration(sec: Long): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun isPlayableInApp(item: EpisodeItem): Boolean {
    val type = item.episode.mediaType.orEmpty()
    val url = item.episode.mediaUrl ?: return false
    return type.startsWith("audio") || type.startsWith("video") ||
        Regex("""\.(mp3|m4a|aac|ogg|opus|mp4|m4v|webm|mov)(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)
}

fun openUrl(context: Context, url: String, mode: OpenMode) {
    val uri = Uri.parse(url)
    try {
        if (mode == OpenMode.INTERNAL) {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
        } else {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

fun shareText(context: Context, title: String, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, "$title\n$url")
    }
    context.startActivity(Intent.createChooser(intent, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Vídeo de YouTube que se puede ver en el reproductor integrado (los directos se abren fuera). */
fun youtubeVideoId(item: EpisodeItem): String? =
    if (item.sourceType == SourceType.YOUTUBE && !item.episode.isLive) YouTubeSource.videoIdFromUrl(item.episode.url) else null

/**
 * Abre un episodio: los podcasts y archivos multimedia en el reproductor integrado. Los vídeos
 * de YouTube según el ajuste: en FeedVibe (guarda el progreso y los marca como vistos solo al
 * terminar) o en la app de YouTube. El resto (Twitch, directos...) en su app o en el navegador.
 */
fun openEpisode(context: Context, container: AppContainer, item: EpisodeItem, navigate: (String) -> Unit) {
    if (isPlayableInApp(item)) {
        navigate(Routes.player(item.episode.id))
        return
    }
    container.appScope.launch {
        val s = container.settings.current()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (s.openMode == OpenMode.INTERNAL && youtubeVideoId(item) != null) {
                YouTubePlayerActivity.start(context, item.episode.id)
            } else {
                openUrl(context, item.episode.url, s.openMode)
            }
        }
    }
}
