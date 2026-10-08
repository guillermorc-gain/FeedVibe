package com.feedvibe.app.ui.components

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.feedvibe.app.data.db.EpisodeItem
import com.feedvibe.app.ui.LocalContainer
import kotlinx.coroutines.launch

/**
 * Selección múltiple (como en Podcast Addict): pulsación larga para empezar, toque para
 * añadir/quitar y pulsación larga sobre otro elemento para seleccionar todo el rango.
 */
@Stable
class SelectionState {
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Orden visible de los elementos; la pantalla lo actualiza para poder seleccionar rangos. */
    var order: List<String> = emptyList()
    private var anchor: String? = null

    val active: Boolean get() = selected.isNotEmpty()
    val count: Int get() = selected.size

    fun isSelected(id: String) = id in selected

    fun toggle(id: String) {
        selected = if (id in selected) selected - id else selected + id
        anchor = id
    }

    fun selectRangeTo(id: String) {
        val a = anchor
        val i = if (a == null) -1 else order.indexOf(a)
        val j = order.indexOf(id)
        if (i < 0 || j < 0) return toggle(id)
        selected = selected + order.subList(minOf(i, j), maxOf(i, j) + 1)
        anchor = id
    }

    fun selectAll() { selected = order.toSet() }

    fun invert() { selected = order.filter { it !in selected }.toSet() }

    /** Selecciona desde el principio de la lista hasta el último seleccionado (o desde él hasta el final). */
    fun selectAbove() {
        val last = order.indexOfLast { it in selected }.takeIf { it >= 0 } ?: return
        selected = selected + order.subList(0, last + 1)
    }

    fun selectBelow() {
        val first = order.indexOfFirst { it in selected }.takeIf { it >= 0 } ?: return
        selected = selected + order.subList(first, order.size)
    }

    fun clear() {
        selected = emptySet()
        anchor = null
    }
}

@Composable
fun rememberSelectionState(): SelectionState = remember { SelectionState() }

/** Barra superior con el número de seleccionados; el botón Atrás cancela la selección. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    selection: SelectionState,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    BackHandler(enabled = selection.active) { selection.clear() }
    TopAppBar(
        title = { Text("${selection.count} seleccionados", fontWeight = FontWeight.Bold) },
        navigationIcon = { IconButton(onClick = { selection.clear() }) { Icon(Icons.Filled.Close, "Cancelar selección") } },
        actions = actions,
        windowInsets = topBarInsets(),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/** Acciones para varios episodios a la vez. */
@Composable
fun EpisodeSelectionBar(selection: SelectionState, items: List<EpisodeItem>) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val feeds = container.feeds
    var menu by remember { mutableStateOf(false) }
    fun ids() = selection.selected.toList()
    fun run(block: suspend (List<String>) -> Unit) {
        val ids = ids()
        selection.clear()
        container.appScope.launch { block(ids) }
    }

    SelectionTopBar(selection) {
        IconButton(onClick = { run { feeds.setWatched(it, true) } }) { Icon(Icons.Filled.DoneAll, "Marcar como vistos") }
        IconButton(onClick = { run { feeds.setWatched(it, false) } }) { Icon(Icons.Filled.RemoveDone, "Marcar como no vistos") }
        IconButton(onClick = { run { feeds.setWatchLater(it, true) } }) { Icon(Icons.Filled.Schedule, "Ver más tarde") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más acciones") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Seleccionar todo") }, onClick = { menu = false; selection.selectAll() })
                DropdownMenuItem(text = { Text("Seleccionar todos los de arriba") }, onClick = { menu = false; selection.selectAbove() })
                DropdownMenuItem(text = { Text("Seleccionar todos los de abajo") }, onClick = { menu = false; selection.selectBelow() })
                DropdownMenuItem(text = { Text("Invertir selección") }, onClick = { menu = false; selection.invert() })
                DropdownMenuItem(text = { Text("Quitar de Ver más tarde") }, onClick = { menu = false; run { feeds.setWatchLater(it, false) } })
                DropdownMenuItem(text = { Text("Añadir a favoritos") }, onClick = { menu = false; run { feeds.setFavorite(it, true) } })
                DropdownMenuItem(text = { Text("Quitar de favoritos") }, onClick = { menu = false; run { feeds.setFavorite(it, false) } })
                DropdownMenuItem(text = { Text("Olvidar progreso de reproducción") }, onClick = { menu = false; run { feeds.resetProgress(it) } })
                DropdownMenuItem(
                    text = { Text("Compartir enlaces") },
                    onClick = {
                        menu = false
                        val chosen = items.filter { selection.isSelected(it.episode.id) }
                        val text = chosen.joinToString("\n\n") { "${it.episode.title}\n${it.episode.url}" }
                        selection.clear()
                        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(intent, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                )
            }
        }
    }
}

/** ¿Lo escrito en la lupa parece la dirección de un canal/feed en vez de una búsqueda? */
fun looksLikeChannelAddress(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.contains(' ')) return false
    return Regex("""^(https?://|feed://|rss://|www\.)\S+$""", RegexOption.IGNORE_CASE).matches(t) ||
        Regex("""^@[\w.\-]{2,}$""").matches(t) ||
        Regex("""^[\w-]+(\.[\w-]+)+(/\S*)?$""").matches(t)
}
