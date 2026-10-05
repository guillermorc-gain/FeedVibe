package com.feedvibe.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.feedvibe.app.data.sources.SourceType

/** Un canal / feed al que el usuario está suscrito. */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val type: SourceType,
    /** Identificador estable dentro de la plataforma (channelId, login, URL del feed...). */
    val sourceKey: String,
    val title: String,
    val description: String = "",
    val imageUrl: String? = null,
    val siteUrl: String? = null,
    val category: String? = null,
    val notify: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
    val lastRefreshed: Long = 0,
    val lastError: String? = null,
    /** Última modificación de los datos sincronizables (para resolver conflictos). */
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Contenido de un episodio tal y como viene del feed. */
@Entity(
    tableName = "episodes",
    indices = [Index("subscriptionId"), Index("publishedAt")],
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val subscriptionId: String,
    val title: String,
    val description: String = "",
    val url: String,
    val mediaUrl: String? = null,
    val mediaType: String? = null,
    val thumbnailUrl: String? = null,
    val publishedAt: Long,
    val durationSec: Long = 0,
    val isLive: Boolean = false,
    val discoveredAt: Long = System.currentTimeMillis(),
)

/**
 * Estado del usuario sobre un episodio. Se guarda aparte del contenido para que
 * el estado que llega de otro dispositivo pueda aplicarse aunque este dispositivo
 * aún no haya descargado el episodio.
 */
@Entity(tableName = "episode_states")
data class EpisodeStateEntity(
    @PrimaryKey val episodeId: String,
    val subscriptionId: String? = null,
    val watched: Boolean = false,
    val watchLater: Boolean = false,
    val favorite: Boolean = false,
    val positionMs: Long = 0,
    val watchedAt: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Episodio + estado + datos del canal, para mostrar en listas. */
data class EpisodeItem(
    @Embedded val episode: EpisodeEntity,
    @ColumnInfo(name = "watched") val watched: Boolean,
    @ColumnInfo(name = "watchLater") val watchLater: Boolean,
    @ColumnInfo(name = "favorite") val favorite: Boolean,
    @ColumnInfo(name = "positionMs") val positionMs: Long,
    @ColumnInfo(name = "watchedAt") val watchedAt: Long,
    @ColumnInfo(name = "channelTitle") val channelTitle: String,
    @ColumnInfo(name = "channelImage") val channelImage: String?,
    @ColumnInfo(name = "sourceType") val sourceType: SourceType,
)

data class SubscriptionWithCount(
    @Embedded val subscription: SubscriptionEntity,
    @ColumnInfo(name = "unwatchedCount") val unwatchedCount: Int,
    @ColumnInfo(name = "totalCount") val totalCount: Int,
    @ColumnInfo(name = "latestAt") val latestAt: Long?,
)
