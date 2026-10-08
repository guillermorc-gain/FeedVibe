package com.feedvibe.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.rotate
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.heightIn
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

    // Grupos (canales) contraídos en cada pestaña: "pestaña|canal".
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    fun groupKey(subId: String) = "$tab|$subId"

    val selection = rememberSelectionState()
    val current = tabs[tab].third
    val groupIds = current.map { it.episode.subscriptionId }.distinct()
    val allCollapsed = groupIds.isNotEmpty() && groupIds.all { groupKey(it) in collapsed }
    selection.order = current.groupBy { it.episode.subscriptionId }.values.flatten().map { it.episode.id }

    ScreenScaffold(
        title = "FeedVibe",
        brand = true,
        topBarOverride = if (selection.active) {
            { EpisodeSelectionBar(selection, current) }
        } else null,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Biblioteca",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (groupIds.size > 1) {
                    IconButton(onClick = {
                        val keys = groupIds.map(::groupKey).toSet()
                        collapsed = if (allCollapsed) collapsed - keys else collapsed + keys
                    }) {
                        Icon(
                            if (allCollapsed) Icons.Filled.UnfoldMore else Icons.Filled.UnfoldLess,
                            if (allCollapsed) "Desplegar todos" else "Contraer todos",
                        )
                    }
                }
            }
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, (label, icon, list) ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i; selection.clear() },
                        text = { Text(label, maxLines = 1) },
                        // El número de episodios, en un globo sobre el icono (cabe aunque la pantalla sea estrecha).
                        icon = {
                            BadgedBox(badge = { if (list.isNotEmpty()) Badge { Text(if (list.size > 999) "999+" else "${list.size}") } }) {
                                Icon(icon, null)
                            }
                        },
                    )
                }
            }
            val (_, icon, list) = tabs[tab]
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (list.isEmpty()) item { EmptyState(icon, "Nada por aquí", empty[tab]) }
                // Agrupados por canal, en el orden en que aparece cada canal.
                list.groupBy { it.episode.subscriptionId }.forEach { (subId, group) ->
                    val first = group.first()
                    val key = groupKey(subId)
                    val isCollapsed = key in collapsed
                    item(key = "h-$subId") {
                        val arrow by animateFloatAsState(if (isCollapsed) -90f else 0f, label = "flecha")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { collapsed = if (isCollapsed) collapsed - key else collapsed + key }
                                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
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
                            Icon(
                                Icons.Filled.ExpandMore,
                                if (isCollapsed) "Desplegar" else "Contraer",
                                Modifier.padding(start = 4.dp).rotate(arrow),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!isCollapsed) items(group, key = { it.episode.id }) {
                        EpisodeRow(it, settings.listStyle, callbacks, showChannel = false, selection = selection, swipeEnabled = settings.swipeToMark)
                    }
                }
            }
        }
    }
}
