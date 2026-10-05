package com.feedvibe.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import java.io.File
import com.feedvibe.app.data.db.EpisodeItem
import com.feedvibe.app.data.prefs.ListStyle
import com.feedvibe.app.data.sources.SourceType
import com.feedvibe.app.ui.formatDuration
import com.feedvibe.app.ui.relativeTime

@Composable
fun SourceBadge(type: SourceType, modifier: Modifier = Modifier) {
    Surface(
        color = Color(type.colorHex),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier,
    ) {
        Text(
            type.label,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun ChannelAvatar(url: String?, title: String, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.trim().take(1).uppercase(),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.4f).sp,
        )
        if (url != null) {
            AsyncImage(model = url, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Avatar del usuario: foto personalizada > foto de Google > icono. */
@Composable
fun UserAvatar(photo: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size * 0.6f))
        if (photo != null) {
            val context = LocalContext.current
            // Ruta local "…/profile.jpg?v=123": la versión fuerza a Coil a recargar al cambiar la foto.
            val model: Any = if (photo.startsWith("/")) {
                ImageRequest.Builder(context)
                    .data(File(photo.substringBefore('?')))
                    .memoryCacheKey(photo)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build()
            } else photo
            AsyncImage(
                model = model,
                contentDescription = "Foto de perfil",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

data class EpisodeCallbacks(
    val onOpen: (EpisodeItem) -> Unit,
    val onToggleWatched: (EpisodeItem) -> Unit,
    val onToggleWatchLater: (EpisodeItem) -> Unit,
    val onToggleFavorite: (EpisodeItem) -> Unit,
    val onMarkOlder: (EpisodeItem) -> Unit,
    val onShare: (EpisodeItem) -> Unit,
    val onOpenChannel: ((EpisodeItem) -> Unit)?,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeRow(
    item: EpisodeItem,
    style: ListStyle,
    callbacks: EpisodeCallbacks,
    showChannel: Boolean = true,
    selection: SelectionState? = null,
) {
    val current by rememberUpdatedState(item)
    val selecting = selection?.active == true
    val selected = selection?.isSelected(item.episode.id) == true
    // Pulsación larga: empieza a seleccionar (o selecciona un rango si ya estás seleccionando).
    val onClick: () -> Unit = {
        if (selecting) selection?.toggle(item.episode.id) else callbacks.onOpen(item)
    }
    val onLongClick: () -> Unit = {
        if (selection == null) callbacks.onToggleWatched(item)
        else if (selecting) selection.selectRangeTo(item.episode.id)
        else selection.toggle(item.episode.id)
    }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                if (value == SwipeToDismissBoxValue.StartToEnd) callbacks.onToggleWatched(current)
                else callbacks.onToggleWatchLater(current)
            }
            false // la fila no desaparece: solo cambia su estado
        },
    )
    if (selecting) {
        // En modo selección no se desliza: solo se marca/desmarca.
        when (style) {
            ListStyle.CARDS -> EpisodeCard(item, callbacks, showChannel, selected, onClick, onLongClick)
            ListStyle.COMPACT -> EpisodeCompact(item, callbacks, showChannel, selected, onClick, onLongClick)
        }
    } else SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val toEnd = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(if (toEnd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary),
                contentAlignment = if (toEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (toEnd) Icons.Filled.CheckCircle else Icons.Filled.Schedule,
                        null,
                        tint = Color.White,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (toEnd) (if (item.watched) "No visto" else "Visto") else (if (item.watchLater) "Quitar de la lista" else "Ver más tarde"),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    ) {
        when (style) {
            ListStyle.CARDS -> EpisodeCard(item, callbacks, showChannel, false, onClick, onLongClick)
            ListStyle.COMPACT -> EpisodeCompact(item, callbacks, showChannel, false, onClick, onLongClick)
        }
    }
}

@Composable
private fun Thumbnail(item: EpisodeItem, modifier: Modifier, selected: Boolean = false) {
    Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant)) {
        AsyncImage(
            model = item.episode.thumbnailUrl ?: item.channelImage,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        val duration = formatDuration(item.episode.durationSec)
        if (item.episode.isLive) {
            Text(
                "EN DIRECTO", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(Color(0xFFE53935), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        } else if (duration.isNotEmpty()) {
            Text(
                duration, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        if (item.watched) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.CheckCircle, "Visto", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
        if (item.positionMs > 0 && item.episode.durationSec > 0 && !item.watched) {
            LinearProgressIndicator(
                progress = { (item.positionMs / 1000f / item.episode.durationSec).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
            )
        }
        if (selected) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.CheckCircle, "Seleccionado", tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
    }
}

@Composable
private fun EpisodeMenu(item: EpisodeItem, callbacks: EpisodeCallbacks) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Opciones") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (item.watched) "Marcar como no visto" else "Marcar como visto") },
                leadingIcon = { Icon(if (item.watched) Icons.Outlined.CheckCircle else Icons.Filled.CheckCircle, null) },
                onClick = { open = false; callbacks.onToggleWatched(item) },
            )
            DropdownMenuItem(
                text = { Text("Marcar este y anteriores como vistos") },
                onClick = { open = false; callbacks.onMarkOlder(item) },
            )
            DropdownMenuItem(
                text = { Text(if (item.watchLater) "Quitar de Ver más tarde" else "Ver más tarde") },
                leadingIcon = { Icon(Icons.Filled.Schedule, null) },
                onClick = { open = false; callbacks.onToggleWatchLater(item) },
            )
            DropdownMenuItem(
                text = { Text(if (item.favorite) "Quitar de favoritos" else "Añadir a favoritos") },
                leadingIcon = { Icon(Icons.Filled.Favorite, null) },
                onClick = { open = false; callbacks.onToggleFavorite(item) },
            )
            callbacks.onOpenChannel?.let { openChannel ->
                DropdownMenuItem(text = { Text("Ir al canal") }, onClick = { open = false; openChannel(item) })
            }
            DropdownMenuItem(text = { Text("Compartir") }, onClick = { open = false; callbacks.onShare(item) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeCard(
    item: EpisodeItem,
    callbacks: EpisodeCallbacks,
    showChannel: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column {
            Thumbnail(item, Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(8.dp), selected)
            Row(Modifier.padding(start = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
                if (showChannel) {
                    ChannelAvatar(item.channelImage, item.channelTitle, 36.dp, Modifier.padding(top = 2.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f).alpha(if (item.watched) 0.6f else 1f)) {
                    Text(
                        item.episode.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (item.watched) FontWeight.Normal else FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    MetaLine(item, showChannel)
                }
                EpisodeMenu(item, callbacks)
            }
        }
    }
}

@Composable
private fun MetaLine(item: EpisodeItem, showChannel: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!item.watched) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
        SourceBadge(item.sourceType)
        Text(
            listOfNotNull(if (showChannel) item.channelTitle else null, relativeTime(item.episode.publishedAt)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.watchLater) Icon(Icons.Filled.Schedule, "Ver más tarde", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
        if (item.favorite) Icon(Icons.Filled.Favorite, "Favorito", Modifier.size(14.dp), tint = Color(0xFFE53935))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeCompact(
    item: EpisodeItem,
    callbacks: EpisodeCallbacks,
    showChannel: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(item, Modifier.width(128.dp).aspectRatio(16f / 9f), selected)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).alpha(if (item.watched) 0.6f else 1f)) {
            Text(
                item.episode.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (item.watched) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            MetaLine(item, showChannel)
        }
        EpisodeMenu(item, callbacks)
    }
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}
