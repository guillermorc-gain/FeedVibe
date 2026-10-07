package com.feedvibe.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.feedvibe.app.data.sources.RssSource
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.components.ScreenScaffold
import com.feedvibe.app.ui.relativeTime
import com.feedvibe.app.ui.shareText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Reproductor integrado para podcasts y archivos de audio/vídeo. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(nav: NavController, episodeId: String) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val item by remember(episodeId) { container.feeds.episode(episodeId) }.collectAsStateWithLifecycle(null)
    val player = remember { ExoPlayer.Builder(context).build() }
    var prepared by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    val isAudio = item?.episode?.mediaType?.startsWith("audio") == true

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(item?.episode?.id) {
        val it = item ?: return@LaunchedEffect
        if (prepared) return@LaunchedEffect
        val media = MediaItem.Builder()
            .setUri(it.episode.mediaUrl)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(it.episode.title).setArtist(it.channelTitle).build()
            )
            .build()
        player.setMediaItem(media)
        player.prepare()
        if (it.positionMs > 0 && !it.watched) player.seekTo(it.positionMs)
        player.playWhenReady = true
        prepared = true
    }

    // Guarda la posición (sincronizada entre dispositivos) y marca como visto al terminar.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // Si el feed no traía la duración, se guarda para poder mostrar la barra de progreso.
                if (state == Player.STATE_READY && player.duration > 0) {
                    container.appScope.launch { container.feeds.setDurationIfUnknown(episodeId, player.duration / 1000) }
                }
                if (state == Player.STATE_ENDED) {
                    container.appScope.launch { container.feeds.setWatched(listOf(episodeId), true) }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            val pos = player.currentPosition
            val ended = player.playbackState == Player.STATE_ENDED
            if (!ended && pos > 5_000) container.appScope.launch { container.feeds.savePosition(episodeId, pos) }
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(30_000)
            if (player.isPlaying) container.feeds.savePosition(episodeId, player.currentPosition)
        }
    }

    val current = item
    ScreenScaffold(
        title = current?.channelTitle ?: "",
        onBack = { nav.popBackStack() },
        actions = {
            if (current != null) {
                IconButton(onClick = { shareText(context, current.episode.title, current.episode.url) }) { Icon(Icons.Filled.Share, "Compartir") }
                IconButton(onClick = { container.appScope.launch { container.feeds.toggleWatched(current) } }) {
                    Icon(Icons.Filled.CheckCircle, if (current.watched) "Marcar como no visto" else "Marcar como visto",
                        tint = if (current.watched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (isAudio && current != null) {
                AsyncImage(
                    model = current.episode.thumbnailUrl ?: current.channelImage,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(32.dp),
                )
            }
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        controllerShowTimeoutMs = if (isAudio) 0 else 3000
                        controllerHideOnTouch = !isAudio
                    }
                },
                modifier = Modifier.fillMaxWidth().then(if (isAudio) Modifier.height(140.dp) else Modifier.aspectRatio(16f / 9f)),
            )
            if (current != null) {
                Column(Modifier.padding(16.dp)) {
                    Text(current.episode.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${current.channelTitle} · ${relativeTime(current.episode.publishedAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1f, 1.25f, 1.5f, 2f).forEach { s ->
                            AssistChip(
                                onClick = { speed = s; player.setPlaybackSpeed(s) },
                                label = { Text("${s}x", fontWeight = if (speed == s) FontWeight.Bold else FontWeight.Normal) },
                                leadingIcon = if (speed == s) { { Icon(Icons.Filled.Speed, null) } } else null,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(RssSource.cleanText(current.episode.description), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
