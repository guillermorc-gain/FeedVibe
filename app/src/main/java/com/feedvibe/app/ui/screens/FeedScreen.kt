package com.feedvibe.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.feedvibe.app.data.prefs.AppSettings
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.Routes
import androidx.compose.foundation.text.KeyboardActions
import com.feedvibe.app.ui.components.AddFromSearchCard
import com.feedvibe.app.ui.components.EmptyState
import com.feedvibe.app.ui.components.EpisodeSelectionBar
import com.feedvibe.app.ui.components.looksLikeChannelAddress
import com.feedvibe.app.ui.components.rememberSelectionState
import com.feedvibe.app.ui.components.EpisodeRow
import com.feedvibe.app.ui.components.ScreenScaffold
import com.feedvibe.app.ui.relativeTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(nav: NavController, settings: AppSettings) {
    val container = LocalContainer.current
    val feeds = container.feeds
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val all by feeds.allEpisodes.collectAsStateWithLifecycle(emptyList())
    val subs by feeds.subscriptionsWithCounts.collectAsStateWithLifecycle(emptyList())
    val categories by feeds.categories.collectAsStateWithLifecycle(emptyList())
    val refreshing by feeds.refreshing.collectAsStateWithLifecycle()
    val lastRefresh by container.settings.lastRefresh.collectAsStateWithLifecycle(0L)

    var typeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var confirmMarkAll by remember { mutableStateOf(false) }

    val subCategory = remember(subs) { subs.associate { it.subscription.id to it.subscription.category } }
    val presentTypes = remember(subs) { subs.map { it.subscription.type }.distinct().sortedBy { it.ordinal } }
    val visible = remember(all, typeFilter, categoryFilter, query, settings.hideWatched) {
        all.filter { item ->
            (!settings.hideWatched || !item.watched) &&
                (typeFilter == null || item.sourceType.name == typeFilter) &&
                (categoryFilter == null || subCategory[item.episode.subscriptionId] == categoryFilter) &&
                (query.isBlank() || item.episode.title.contains(query, true) || item.channelTitle.contains(query, true))
        }
    }
    val callbacks = rememberEpisodeCallbacks(nav)
    val selection = rememberSelectionState()
    selection.order = visible.map { it.episode.id }
    val isAddress = looksLikeChannelAddress(query)

    fun refresh() = scope.launch {
        val r = feeds.refreshAll()
        val n = r.newEpisodes.sumOf { it.episodes.size }
        val msg = buildString {
            append(if (n == 0) "No hay episodios nuevos" else "$n episodios nuevos")
            if (r.errors > 0) append(" · ${r.errors} canales con error")
        }
        snackbar.showSnackbar(msg)
    }

    ScreenScaffold(
        title = "Novedades",
        snackbar = snackbar,
        topBarOverride = if (selection.active) {
            { EpisodeSelectionBar(selection, visible) }
        } else null,
        titleContent = if (searching) {
            {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Buscar o pegar la dirección de un canal") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        if (isAddress) { nav.navigate(Routes.add(query.trim())); query = ""; searching = false }
                    }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else null,
        actions = {
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Buscar")
            }
            IconButton(onClick = { refresh() }, enabled = !refreshing) { Icon(Icons.Filled.Refresh, "Actualizar") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (settings.hideWatched) "Mostrar vistos" else "Ocultar vistos") },
                        leadingIcon = { Icon(if (settings.hideWatched) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, null) },
                        onClick = {
                            menu = false
                            scope.launch { container.settings.update { it.copy(hideWatched = !it.hideWatched) } }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Marcar lista como vista") },
                        leadingIcon = { Icon(Icons.Filled.DoneAll, null) },
                        onClick = { menu = false; confirmMarkAll = true },
                    )
                }
            }
        },
        floatingActionButton = {
            if (!selection.active) ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.add()) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Añadir") },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (isAddress) {
                    item {
                        AddFromSearchCard(query) {
                            nav.navigate(Routes.add(query.trim()))
                            query = ""
                            searching = false
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = typeFilter == null && categoryFilter == null,
                            onClick = { typeFilter = null; categoryFilter = null },
                            label = { Text("Todo") },
                        )
                        presentTypes.forEach { t ->
                            FilterChip(
                                selected = typeFilter == t.name,
                                onClick = { typeFilter = if (typeFilter == t.name) null else t.name },
                                label = { Text(t.label) },
                            )
                        }
                        categories.forEach { c ->
                            FilterChip(
                                selected = categoryFilter == c,
                                onClick = { categoryFilter = if (categoryFilter == c) null else c },
                                label = { Text("# $c") },
                            )
                        }
                    }
                }
                item {
                    Text(
                        buildString {
                            append("${visible.count { !it.watched }} sin ver")
                            if (lastRefresh > 0) append(" · actualizado ${relativeTime(lastRefresh)}")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (visible.isEmpty() && !isAddress) {
                    item {
                        if (subs.isEmpty()) {
                            EmptyState(
                                Icons.Filled.NewReleases,
                                "Aún no sigues ningún canal",
                                "Añade canales de YouTube, Twitch, Dailymotion, Vimeo, podcasts o cualquier web con RSS.",
                            ) { Button(onClick = { nav.navigate(Routes.add()) }) { Text("Añadir canal") } }
                        } else {
                            EmptyState(Icons.Filled.DoneAll, "¡Estás al día!", "No tienes episodios pendientes por ver.")
                        }
                    }
                }
                items(visible, key = { it.episode.id }) { item ->
                    EpisodeRow(item, settings.listStyle, callbacks, selection = selection)
                }
            }
        }
    }

    if (confirmMarkAll) {
        AlertDialog(
            onDismissRequest = { confirmMarkAll = false },
            title = { Text("Marcar como vistos") },
            text = { Text("Se marcarán como vistos los ${visible.count { !it.watched }} episodios de la lista actual en todos tus dispositivos.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmMarkAll = false
                    val list = visible
                    container.appScope.launch { feeds.markListWatched(list, true) }
                }) { Text("Marcar") }
            },
            dismissButton = { TextButton(onClick = { confirmMarkAll = false }) { Text("Cancelar") } },
        )
    }
}
