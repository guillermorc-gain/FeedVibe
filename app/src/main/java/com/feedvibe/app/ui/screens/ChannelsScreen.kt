package com.feedvibe.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.feedvibe.app.data.db.SubscriptionWithCount
import com.feedvibe.app.data.prefs.AppSettings
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.Routes
import com.feedvibe.app.ui.components.AddFromSearchCard
import com.feedvibe.app.ui.components.ChannelAvatar
import com.feedvibe.app.ui.components.EmptyState
import com.feedvibe.app.ui.components.ScreenScaffold
import com.feedvibe.app.ui.components.SelectionState
import com.feedvibe.app.ui.components.SelectionTopBar
import com.feedvibe.app.ui.components.SourceBadge
import com.feedvibe.app.ui.components.looksLikeChannelAddress
import com.feedvibe.app.ui.components.rememberSelectionState
import com.feedvibe.app.ui.relativeTime
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

enum class ChannelSort(val label: String) {
    NAME("Nombre (A → Z)"),
    NAME_DESC("Nombre (Z → A)"),
    UNWATCHED("Más episodios sin ver"),
    LATEST("Publicado más recientemente"),
    ADDED("Añadidos recientemente"),
    TYPE("Plataforma"),
}

private const val MIN_SIZE = 56f
private const val MAX_SIZE = 200f

@Composable
fun ChannelsScreen(nav: NavController, settings: AppSettings) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val subs by container.feeds.subscriptionsWithCounts.collectAsStateWithLifecycle(emptyList())
    val sort = ChannelSort.entries.firstOrNull { it.name == settings.channelSort } ?: ChannelSort.NAME
    val grid = settings.channelGrid
    var sortMenu by remember { mutableStateOf(false) }
    var zoomBar by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // Tamaño "en vivo" mientras se pellizca/arrastra; se guarda al soltar.
    var size by remember(settings.channelGridSize) { mutableFloatStateOf(settings.channelGridSize.toFloat()) }
    val selection = rememberSelectionState()
    val isAddress = looksLikeChannelAddress(query)

    fun saveSize() = scope.launch { container.settings.update { it.copy(channelGridSize = size.toInt()) } }

    val collator = remember { Collator.getInstance(Locale("es")).apply { strength = Collator.PRIMARY } }
    val onlyUnwatched = settings.channelsOnlyUnwatched
    val withNew = remember(subs) { subs.count { it.unwatchedCount > 0 } }
    val filtered = remember(subs, query, isAddress, onlyUnwatched) {
        val searched = if (query.isBlank() || isAddress) subs else subs.filter { it.subscription.title.contains(query.trim(), ignoreCase = true) }
        // Al buscar se muestran todos, aunque estén al día.
        if (onlyUnwatched && query.isBlank()) searched.filter { it.unwatchedCount > 0 } else searched
    }
    val sorted = remember(filtered, sort) {
        val byName = compareBy<SubscriptionWithCount, String>(collator) { it.subscription.title }
        when (sort) {
            ChannelSort.NAME -> filtered.sortedWith(byName)
            ChannelSort.NAME_DESC -> filtered.sortedWith(byName.reversed())
            ChannelSort.UNWATCHED -> filtered.sortedWith(compareByDescending<SubscriptionWithCount> { it.unwatchedCount }.then(byName))
            ChannelSort.LATEST -> filtered.sortedByDescending { it.latestAt ?: 0 }
            ChannelSort.ADDED -> filtered.sortedByDescending { it.subscription.addedAt }
            ChannelSort.TYPE -> filtered.sortedWith(compareBy<SubscriptionWithCount> { it.subscription.type.ordinal }.then(byName))
        }
    }
    val grouped = remember(sorted) { sorted.groupBy { it.subscription.category?.takeIf { c -> c.isNotBlank() } } }
    selection.order = sorted.map { it.subscription.id }

    ScreenScaffold(
        title = "Canales (${subs.size})",
        topBarOverride = if (selection.active) {
            { ChannelSelectionBar(selection) }
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
            if (grid) {
                IconButton(onClick = { zoomBar = !zoomBar }) { Icon(Icons.Filled.ZoomIn, "Tamaño de los logos") }
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Ordenar") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    ChannelSort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label, fontWeight = if (s == sort) FontWeight.Bold else FontWeight.Normal) },
                            leadingIcon = { if (s == sort) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                sortMenu = false
                                scope.launch { container.settings.update { it.copy(channelSort = s.name) } }
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Importar OPML (Podcast Addict…)") },
                        leadingIcon = { Icon(Icons.Filled.FileOpen, null) },
                        onClick = { sortMenu = false; nav.navigate(Routes.IMPORT_OPML) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (grid) "Ver como lista" else "Ver como cuadrícula") },
                        leadingIcon = { Icon(if (grid) Icons.Filled.ViewList else Icons.Filled.GridView, null) },
                        onClick = {
                            sortMenu = false
                            scope.launch { container.settings.update { it.copy(channelGrid = !it.channelGrid) } }
                        },
                    )
                }
            }
        },
        floatingActionButton = {
            if (!selection.active) {
                FloatingActionButton(onClick = { nav.navigate(Routes.add()) }) { Icon(Icons.Filled.Add, "Añadir canal") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (zoomBar && grid) {
                ZoomBar(
                    size = size,
                    showNames = settings.showChannelNames,
                    onSize = { size = it },
                    onSizeDone = { saveSize() },
                    onShowNames = { v -> scope.launch { container.settings.update { it.copy(showChannelNames = v) } } },
                )
            }
            if (subs.isNotEmpty() && !searching) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = onlyUnwatched,
                        onClick = { scope.launch { container.settings.update { it.copy(channelsOnlyUnwatched = true) } } },
                        label = { Text("Con episodios sin ver ($withNew)") },
                    )
                    FilterChip(
                        selected = !onlyUnwatched,
                        onClick = { scope.launch { container.settings.update { it.copy(channelsOnlyUnwatched = false) } } },
                        label = { Text("Todos (${subs.size})") },
                    )
                }
            }
            if (isAddress) {
                AddFromSearchCard(query) {
                    nav.navigate(Routes.add(query.trim()))
                    query = ""
                    searching = false
                }
            }
            if (subs.isEmpty()) {
                EmptyState(
                    Icons.Filled.Subscriptions,
                    "Sin canales",
                    "Pulsa la lupa y pega la dirección de un canal de YouTube, Twitch, Dailymotion, Vimeo, Odysee, un podcast o una web con RSS.",
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(onClick = { nav.navigate(Routes.add()) }) { Text("Añadir canal") }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { nav.navigate(Routes.IMPORT_OPML) }) { Text("Importar desde Podcast Addict (OPML)") }
                    }
                }
            } else if (sorted.isEmpty() && onlyUnwatched && query.isBlank()) {
                EmptyState(
                    Icons.Filled.DoneAll,
                    "¡Estás al día!",
                    "Ningún canal tiene episodios sin ver.",
                ) {
                    Button(onClick = { scope.launch { container.settings.update { it.copy(channelsOnlyUnwatched = false) } } }) {
                        Text("Ver todos los canales")
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = if (grid) GridCells.Adaptive(size.dp) else GridCells.Fixed(1),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (grid) (size / 14f).coerceIn(4f, 10f).dp else 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        // Pellizcar con dos dedos agranda o reduce los logos (como en Podcast Addict).
                        .pointerInput(grid) {
                            if (!grid) return@pointerInput
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var zoomed = false
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.count { it.pressed } >= 2) {
                                        val zoom = event.calculateZoom()
                                        if (zoom != 1f) {
                                            size = (size * zoom).coerceIn(MIN_SIZE, MAX_SIZE)
                                            zoomed = true
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                                if (zoomed) saveSize()
                            }
                        },
                ) {
                    grouped.forEach { (category, list) ->
                        if (grouped.size > 1) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    category ?: "Sin categoría",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                                )
                            }
                        }
                        items(list, key = { it.subscription.id }) { s ->
                            val id = s.subscription.id
                            val onClick = {
                                if (selection.active) selection.toggle(id) else nav.navigate(Routes.channel(id))
                            }
                            val onLongClick = { if (selection.active) selection.selectRangeTo(id) else selection.toggle(id) }
                            if (grid) ChannelTile(s, size, settings.showChannelNames, selection.isSelected(id), onClick, onLongClick)
                            else ChannelListRow(s, selection.isSelected(id), onClick, onLongClick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomBar(size: Float, showNames: Boolean, onSize: (Float) -> Unit, onSizeDone: () -> Unit, onShowNames: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onSize((size - 16f).coerceAtLeast(MIN_SIZE)); onSizeDone() }) { Icon(Icons.Filled.ZoomOut, "Más pequeños") }
                Slider(
                    value = size,
                    onValueChange = onSize,
                    onValueChangeFinished = onSizeDone,
                    valueRange = MIN_SIZE..MAX_SIZE,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onSize((size + 16f).coerceAtMost(MAX_SIZE)); onSizeDone() }) { Icon(Icons.Filled.ZoomIn, "Más grandes") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onShowNames(!showNames) }) {
                Text("Mostrar nombres", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
                Switch(checked = showNames, onCheckedChange = onShowNames)
            }
            Text(
                "Consejo: también puedes pellizcar la cuadrícula con dos dedos.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
            )
        }
    }
}

/** Logo cuadrado tipo Podcast Addict, con el número de episodios sin ver. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelTile(
    s: SubscriptionWithCount,
    size: Float,
    showName: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val sub = s.subscription
    val shape = RoundedCornerShape((size / 9f).coerceIn(6f, 16f).dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                sub.title.trim().take(1).uppercase(),
                fontSize = (size * 0.38f).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (sub.imageUrl != null) {
                AsyncImage(sub.imageUrl, sub.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (s.unwatchedCount > 0) {
                Text(
                    if (s.unwatchedCount > 999) "999+" else "${s.unwatchedCount}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size / 9f).coerceIn(10f, 15f).sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (size >= 90f) {
                SourceBadge(sub.type, Modifier.align(Alignment.BottomStart).padding(4.dp))
            }
            if (sub.lastError != null) {
                Icon(
                    Icons.Filled.ErrorOutline, "Error al actualizar",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.White, CircleShape),
                )
            }
            if (selected) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.CheckCircle, "Seleccionado", tint = Color.White, modifier = Modifier.size((size / 3f).dp))
                }
            }
        }
        if (showName) {
            Spacer(Modifier.height(4.dp))
            Text(
                sub.title,
                style = if (size < 90f) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelListRow(s: SubscriptionWithCount, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                ChannelAvatar(s.subscription.imageUrl, s.subscription.title, 48.dp)
                if (selected) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomEnd))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.subscription.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceBadge(s.subscription.type)
                    Text(
                        "${s.totalCount} episodios" + (s.latestAt?.let { " · último ${relativeTime(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (s.unwatchedCount > 0) Badge { Text("${s.unwatchedCount}") }
        }
    }
}

/** Acciones para varios canales a la vez. */
@Composable
private fun ChannelSelectionBar(selection: SelectionState) {
    val container = LocalContainer.current
    val feeds = container.feeds
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editCategory by remember { mutableStateOf(false) }
    fun run(block: suspend (Set<String>) -> Unit) {
        val ids = selection.selected
        selection.clear()
        container.appScope.launch { block(ids) }
    }

    SelectionTopBar(selection) {
        IconButton(onClick = { run { feeds.markSubscriptionsWatched(it) } }) { Icon(Icons.Filled.DoneAll, "Marcar todo como visto") }
        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Dejar de seguir") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más acciones") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Seleccionar todos") }, onClick = { menu = false; selection.selectAll() })
                DropdownMenuItem(text = { Text("Invertir selección") }, onClick = { menu = false; selection.invert() })
                DropdownMenuItem(text = { Text("Actualizar ahora") }, onClick = { menu = false; run { feeds.refreshAll(it) } })
                DropdownMenuItem(text = { Text("Cambiar categoría") }, onClick = { menu = false; editCategory = true })
                DropdownMenuItem(
                    text = { Text("Activar notificaciones") },
                    onClick = { menu = false; run { ids -> feeds.updateSubscriptions(ids) { it.copy(notify = true) } } },
                )
                DropdownMenuItem(
                    text = { Text("Silenciar notificaciones") },
                    onClick = { menu = false; run { ids -> feeds.updateSubscriptions(ids) { it.copy(notify = false) } } },
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Dejar de seguir") },
            text = { Text("¿Dejar de seguir ${selection.count} canales? También se quitarán de tus otros dispositivos.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; run { feeds.unsubscribeMany(it) } }) { Text("Dejar de seguir") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }

    if (editCategory) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editCategory = false },
            title = { Text("Categoría para ${selection.count} canales") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text("Vacío = sin categoría") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editCategory = false
                    val cat = text.trim().ifBlank { null }
                    run { ids -> feeds.updateSubscriptions(ids) { it.copy(category = cat) } }
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { editCategory = false }) { Text("Cancelar") } },
        )
    }
}
