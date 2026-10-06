package com.feedvibe.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import com.feedvibe.app.ui.Routes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.feedvibe.app.data.prefs.AppSettings
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.components.ChannelAvatar
import com.feedvibe.app.ui.components.EpisodeRow
import com.feedvibe.app.ui.components.EpisodeSelectionBar
import com.feedvibe.app.ui.components.rememberSelectionState
import com.feedvibe.app.ui.components.ScreenScaffold
import com.feedvibe.app.ui.components.SourceBadge
import com.feedvibe.app.ui.openUrl
import com.feedvibe.app.ui.relativeTime
import com.feedvibe.app.ui.shareText
import com.feedvibe.app.data.sources.RssSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelDetailScreen(nav: NavController, settings: AppSettings, subId: String) {
    val container = LocalContainer.current
    val feeds = container.feeds
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val sub by remember(subId) { feeds.subscription(subId) }.collectAsStateWithLifecycle(null)
    val episodes by remember(subId) { feeds.episodesFor(subId) }.collectAsStateWithLifecycle(emptyList())
    val refreshing by feeds.refreshing.collectAsStateWithLifecycle()
    var onlyUnwatched by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editCategory by remember { mutableStateOf(false) }
    var askFullHistory by remember { mutableStateOf(false) }
    val historyProgress by feeds.historyProgress.collectAsStateWithLifecycle()
    val loadingHistory = historyProgress[subId]
    val callbacks = rememberEpisodeCallbacks(nav, allowOpenChannel = false)
    val s = sub

    val filtered = if (onlyUnwatched) episodes.filter { !it.watched } else episodes
    val visible = if (settings.oldestFirst) filtered.asReversed() else filtered
    val selection = rememberSelectionState()
    selection.order = visible.map { it.episode.id }
    val unwatched = episodes.count { !it.watched }

    ScreenScaffold(
        title = s?.title ?: "",
        onBack = { nav.popBackStack() },
        snackbar = snackbar,
        topBarOverride = if (selection.active) {
            { EpisodeSelectionBar(selection, visible) }
        } else null,
        actions = {
            if (s != null) {
                IconButton(onClick = { scope.launch { feeds.updateSubscription(s.copy(notify = !s.notify)) } }) {
                    Icon(if (s.notify) Icons.Filled.Notifications else Icons.Filled.NotificationsOff, "Notificaciones")
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Marcar todo como visto") },
                            leadingIcon = { Icon(Icons.Filled.DoneAll, null) },
                            onClick = { menu = false; container.appScope.launch { feeds.markAllWatched(subId) } },
                        )
                        if (feeds.canLoadFullHistory(s)) {
                            DropdownMenuItem(
                                text = { Text(if (s.fullHistory) "Volver a cargar todos los vídeos" else "Cargar todos los vídeos") },
                                leadingIcon = { Icon(Icons.Filled.History, null) },
                                onClick = { menu = false; askFullHistory = true },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Categoría") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; editCategory = true },
                        )
                        s.siteUrl?.let { url ->
                            DropdownMenuItem(
                                text = { Text("Abrir web del canal") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                                onClick = { menu = false; openUrl(context, url, settings.openMode) },
                            )
                            DropdownMenuItem(
                                text = { Text("Compartir canal") },
                                leadingIcon = { Icon(Icons.Filled.Share, null) },
                                onClick = { menu = false; shareText(context, s.title, url) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Dejar de seguir", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    val r = feeds.refreshOne(subId)
                    snackbar.showSnackbar(if (r.errors > 0) "Error al actualizar" else "Actualizado")
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item {
                    if (s != null) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ChannelAvatar(s.imageUrl, s.title, 72.dp)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        SourceBadge(s.type)
                                        s.category?.let { Text("# $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                                    }
                                    Text(
                                        "$unwatched sin ver de ${episodes.size}" +
                                            (if (s.lastRefreshed > 0) " · ${relativeTime(s.lastRefreshed)}" else ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (s.description.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    RssSource.cleanText(s.description), style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            s.lastError?.let {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { scope.launch { feeds.refreshOne(subId) } }) { Icon(Icons.Filled.Refresh, "Reintentar") }
                                }
                            }
                            if (feeds.canLoadFullHistory(s) && (!s.fullHistory || loadingHistory != null)) {
                                Spacer(Modifier.height(12.dp))
                                FullHistoryCard(
                                    shown = episodes.size,
                                    progress = loadingHistory,
                                    onLoad = { askFullHistory = true },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                FilterChip(selected = !onlyUnwatched, onClick = { onlyUnwatched = false }, label = { Text("Todos") })
                                FilterChip(selected = onlyUnwatched, onClick = { onlyUnwatched = true }, label = { Text("Sin ver ($unwatched)") })
                                Spacer(Modifier.weight(1f))
                                AssistChip(
                                    onClick = { scope.launch { container.settings.update { it.copy(oldestFirst = !it.oldestFirst) } } },
                                    label = { Text(if (settings.oldestFirst) "Antiguos primero" else "Recientes primero") },
                                    leadingIcon = { Icon(if (settings.oldestFirst) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward, null) },
                                )
                            }
                        }
                    }
                }
                items(visible, key = { it.episode.id }) { item ->
                    EpisodeRow(item, settings.listStyle, callbacks, showChannel = false, selection = selection)
                }
            }
        }
    }

    if (confirmDelete && s != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Dejar de seguir") },
            text = { Text("¿Quieres dejar de seguir \"${s.title}\"? Se eliminará también de tus otros dispositivos.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    container.appScope.launch { feeds.unsubscribe(subId) }
                    nav.popBackStack()
                }) { Text("Dejar de seguir") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }

    if (askFullHistory) {
        var markOld by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { askFullHistory = false },
            title = { Text("Cargar todos los vídeos") },
            text = {
                Column {
                    Text("Se descargará la lista completa de vídeos del canal desde YouTube. En canales con muchos vídeos puede tardar uno o dos minutos.")
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { markOld = !markOld }) {
                        Checkbox(checked = markOld, onCheckedChange = { markOld = it })
                        Text("Marcar como vistos los vídeos antiguos que se añadan")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askFullHistory = false
                    // En appScope para que la carga siga aunque salgas de la pantalla.
                    container.appScope.launch(Dispatchers.Main) {
                        try {
                            val n = feeds.loadFullHistory(subId, markOld)
                            snackbar.showSnackbar(if (n == 0) "No había vídeos nuevos" else "Añadidos $n vídeos")
                        } catch (e: Exception) {
                            snackbar.showSnackbar(e.message ?: "No se pudo cargar el historial")
                        }
                    }
                }) { Text("Cargar") }
            },
            dismissButton = { TextButton(onClick = { askFullHistory = false }) { Text("Cancelar") } },
        )
    }

    if (editCategory && s != null) {
        var text by remember { mutableStateOf(s.category.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editCategory = false },
            title = { Text("Categoría") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Ej.: Tecnología, Música…") })
            },
            confirmButton = {
                TextButton(onClick = {
                    editCategory = false
                    scope.launch { feeds.updateSubscription(s.copy(category = text.trim().ifBlank { null })) }
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { editCategory = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun FullHistoryCard(shown: Int, progress: Int?, onLoad: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (progress != null) {
                    Text("Cargando historial…", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("$progress vídeos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Text("Mostrando los últimos $shown vídeos", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Carga el canal entero para ver todo lo que te falta", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            if (progress == null) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onLoad) { Text("Cargar todos") }
            }
        }
    }
}
