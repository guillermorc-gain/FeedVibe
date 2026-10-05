package com.feedvibe.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.feedvibe.app.data.prefs.AppSettings
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.components.EmptyState
import com.feedvibe.app.ui.components.EpisodeRow
import com.feedvibe.app.ui.components.ScreenScaffold

@Composable
fun LibraryScreen(nav: NavController, settings: AppSettings) {
    val feeds = LocalContainer.current.feeds
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val later by feeds.watchLater.collectAsStateWithLifecycle(emptyList())
    val progress by feeds.inProgress.collectAsStateWithLifecycle(emptyList())
    val favs by feeds.favorites.collectAsStateWithLifecycle(emptyList())
    val history by feeds.history.collectAsStateWithLifecycle(emptyList())
    val callbacks = rememberEpisodeCallbacks(nav)

    val tabs = listOf(
        Triple("Más tarde", Icons.Filled.Schedule, later),
        Triple("En curso", Icons.Filled.PlayCircle, progress),
        Triple("Favoritos", Icons.Filled.Favorite, favs),
        Triple("Historial", Icons.Filled.History, history),
    )
    val empty = listOf(
        "Desliza un episodio hacia la izquierda para guardarlo aquí.",
        "Los podcasts que empieces a escuchar aparecerán aquí.",
        "Marca episodios como favoritos desde su menú.",
        "Aquí verás lo último que has marcado como visto.",
    )

    ScreenScaffold(title = "Biblioteca") { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, (label, icon, list) ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(if (list.isEmpty()) label else "$label (${list.size})", maxLines = 1) },
                        icon = { Icon(icon, null) },
                    )
                }
            }
            val (_, icon, list) = tabs[tab]
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (list.isEmpty()) item { EmptyState(icon, "Nada por aquí", empty[tab]) }
                items(list, key = { it.episode.id }) { EpisodeRow(it, settings.listStyle, callbacks) }
            }
        }
    }
}
