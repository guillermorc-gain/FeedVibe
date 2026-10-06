package com.feedvibe.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import com.feedvibe.app.ui.components.ChannelAvatar
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
import com.feedvibe.app.ui.components.EpisodeSelectionBar
import com.feedvibe.app.ui.components.rememberSelectionState
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

    val selection = rememberSelectionState()
    val current = tabs[tab].third
    selection.order = current.groupBy { it.episode.subscriptionId }.values.flatten().map { it.episode.id }

    ScreenScaffold(
        title = "FeedVibe",
        topBarOverride = if (selection.active) {
            { EpisodeSelectionBar(selection, current) }
        } else null,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Biblioteca",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            )
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, (label, icon, list) ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i; selection.clear() },
                        text = { Text(if (list.isEmpty()) label else "$label (${list.size})", maxLines = 1) },
                        icon = { Icon(icon, null) },
                    )
                }
            }
            val (_, icon, list) = tabs[tab]
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (list.isEmpty()) item { EmptyState(icon, "Nada por aquí", empty[tab]) }
                // Agrupados por canal, en el orden en que aparece cada canal.
                list.groupBy { it.episode.subscriptionId }.forEach { (subId, group) ->
                    val first = group.first()
                    item(key = "h-$subId") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ChannelAvatar(first.channelImage, first.channelTitle, 28.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                first.channelTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${group.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(group, key = { it.episode.id }) {
                        EpisodeRow(it, settings.listStyle, callbacks, showChannel = false, selection = selection, swipeEnabled = settings.swipeToMark)
                    }
                }
            }
        }
    }
}
