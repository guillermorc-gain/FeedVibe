package com.feedvibe.app.radio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.feedvibe.app.data.prefs.SettingsRepository
import com.feedvibe.app.data.radio.Station
import com.feedvibe.app.data.radio.Stream
import com.feedvibe.app.data.radio.TuneIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lo que suena ahora. */
data class NowPlaying(
    val station: Station,
    val stream: Stream?,
    val playing: Boolean = false,
    val loading: Boolean = true,
    /** Canción o programa que anuncia la emisora (si lo emite). */
    val track: String? = null,
    val error: String? = null,
)

/** Mando de la radio para la app: elige la emisión según la calidad y habla con [RadioService]. */
class RadioPlayer(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _now = MutableStateFlow<NowPlaying?>(null)
    val now: StateFlow<NowPlaying?> = _now.asStateFlow()

    private var controller: MediaController? = null

    private suspend fun controller(): MediaController {
        controller?.let { return it }
        val token = SessionToken(context, ComponentName(context, RadioService::class.java))
        val c = MediaController.Builder(context, token).buildAsync().await()
        c.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _now.value = _now.value?.copy(playing = isPlaying, loading = !isPlaying && c.playbackState == Player.STATE_BUFFERING)
            }

            override fun onPlaybackStateChanged(state: Int) {
                _now.value = _now.value?.copy(loading = state == Player.STATE_BUFFERING)
            }

            override fun onMediaMetadataChanged(m: MediaMetadata) {
                // Las emisoras anuncian «Artista - Canción» en los datos de la emisión.
                val track = m.title?.toString()?.takeIf { it.isNotBlank() && it != _now.value?.station?.name }
                if (track != null) _now.value = _now.value?.copy(track = track)
            }

            override fun onPlayerError(error: PlaybackException) {
                _now.value = _now.value?.copy(playing = false, loading = false, error = "No se puede escuchar esta emisora ahora (${error.errorCodeName})")
            }
        })
        controller = c
        return c
    }

    /** Elige la emisión según el ajuste: máxima calidad o ahorro de datos. */
    private suspend fun pick(streams: List<Stream>): Stream {
        val saver = settings.current().radioQuality == "SAVER"
        // ExoPlayer reproduce también HLS; se prefiere la emisión directa si la calidad es la misma.
        val sorted = streams.sortedWith(compareBy<Stream> { it.bitrate }.thenBy { if (it.isHls) 0 else 1 })
        return if (saver) sorted.firstOrNull { it.bitrate >= 48 } ?: sorted.first() else sorted.last()
    }

    fun play(station: Station) {
        _now.value = NowPlaying(station, null)
        scope.launch {
            runCatching {
                val stream = pick(TuneIn.streams(station.id))
                _now.value = _now.value?.takeIf { it.station.id == station.id }?.copy(stream = stream) ?: return@launch
                val item = MediaItem.Builder()
                    .setUri(stream.url)
                    .setMediaId(station.id)
                    // El enlace también aquí: al pasar al servicio, el de setUri se pierde.
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(stream.url)).build())
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(station.name)
                            .setArtist(station.subtitle.ifBlank { "Radio en directo" })
                            .setArtworkUri(station.image?.let(Uri::parse))
                            .build(),
                    )
                    .build()
                withContext(Dispatchers.Main) {
                    val c = controller()
                    c.setMediaItem(item)
                    c.prepare()
                    c.play()
                }
                settings.addRecentStation(station)
            }.onFailure { e ->
                _now.value = _now.value?.copy(loading = false, playing = false, error = e.message ?: "No se pudo conectar")
            }
        }
    }

    /** Temporizador para dormir: hora a la que se parará (null = sin temporizador). */
    private val _sleepAt = MutableStateFlow<Long?>(null)
    val sleepAt: StateFlow<Long?> = _sleepAt.asStateFlow()
    private var sleepJob: kotlinx.coroutines.Job? = null

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) { _sleepAt.value = null; return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        _sleepAt.value = at
        sleepJob = scope.launch {
            kotlinx.coroutines.delay(minutes * 60_000L)
            _sleepAt.value = null
            stop()
        }
    }

    fun toggle() = scope.launch(Dispatchers.Main) {
        val c = controller ?: return@launch
        if (c.isPlaying) c.pause() else c.play()
    }

    fun stop() = scope.launch(Dispatchers.Main) {
        controller?.run { stop(); clearMediaItems() }
        _now.value = null
    }
}
