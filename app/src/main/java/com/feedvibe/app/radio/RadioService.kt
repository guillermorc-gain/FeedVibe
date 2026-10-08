package com.feedvibe.app.radio

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.feedvibe.app.FeedVibeApp
import com.feedvibe.app.MainActivity
import com.feedvibe.app.data.radio.Station
import com.feedvibe.app.data.radio.TuneIn
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future

/**
 * Reproduce la radio en segundo plano. Es un servicio de biblioteca multimedia: los auriculares
 * y altavoces Bluetooth, la pantalla de bloqueo y el coche (Android Auto) la controlan, y en el
 * coche se ven las favoritas y las recientes para elegir emisora.
 */
class RadioService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings get() = (application as FeedVibeApp).container.settings

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            // Se pausa al desconectar los auriculares.
            .setHandleAudioBecomingNoisy(true)
            // CPU y Wi‑Fi despiertas con la pantalla apagada.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    // Al quitar la app de recientes: si no suena nada, fuera.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).build())
        .build()

    private fun stationItem(s: Station) = MediaItem.Builder()
        .setMediaId(s.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(s.name)
                .setArtist(s.subtitle.ifBlank { "Radio en directo" })
                .setArtworkUri(s.image?.let(Uri::parse))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    /** Busca la emisora entre favoritas y recientes (para el coche, que solo da su id). */
    private suspend fun findStation(id: String): Station? =
        settings.radioFavorites.first().firstOrNull { it.id == id } ?: settings.radioRecents.first().firstOrNull { it.id == id }

    /** Deja cada elemento listo para sonar: con su enlace de emisión (lo pide si no lo trae). */
    private suspend fun playable(item: MediaItem): MediaItem {
        val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
        if (uri != null) return item.buildUpon().setUri(uri).build()
        val station = findStation(item.mediaId)
        val streams = TuneIn.streams(item.mediaId)
        val saver = settings.current().radioQuality == "SAVER"
        val sorted = streams.sortedWith(compareBy<com.feedvibe.app.data.radio.Stream> { it.bitrate }.thenBy { if (it.isHls) 0 else 1 })
        val stream = if (saver) sorted.firstOrNull { it.bitrate >= 48 } ?: sorted.first() else sorted.last()
        return (station?.let(::stationItem) ?: item).buildUpon().setUri(stream.url).build()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "FeedVibe Radio"), params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val items = when (parentId) {
                ROOT -> listOf(folder(FAVORITES, "Favoritas"), folder(RECENTS, "Escuchadas hace poco"))
                FAVORITES -> settings.radioFavorites.first().map(::stationItem)
                RECENTS -> settings.radioRecents.first().map(::stationItem)
                else -> emptyList()
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val s = findStation(mediaId)
            if (s != null) LibraryResult.ofItem(stationItem(s), null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.map { playable(it) }.toMutableList()
        }
    }

    companion object {
        private const val ROOT = "root"
        private const val FAVORITES = "favorites"
        private const val RECENTS = "recents"
    }
}
