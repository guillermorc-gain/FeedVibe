package com.feedvibe.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.feedvibe.app.data.db.SubscriptionWithCount
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.Routes
import com.feedvibe.app.ui.components.ChannelAvatar
import com.feedvibe.app.ui.components.EmptyState
import com.feedvibe.app.ui.components.ScreenScaffold
import com.feedvibe.app.ui.components.SourceBadge
import com.feedvibe.app.ui.relativeTime

private enum class ChannelSort(val label: String) { NAME("Nombre"), UNWATCHED("Sin ver"), LATEST("Más recientes"), TYPE("Plataforma") }

@Composable
fun ChannelsScreen(nav: NavController) {
    val container = LocalContainer.current
    val subs by container.feeds.subscriptionsWithCounts.collectAsStateWithLifecycle(emptyList())
    var sort by rememberSaveable { mutableStateOf(ChannelSort.NAME) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var sortMenu by remember { mutableStateOf(false) }

    val sorted = remember(subs, sort) {
        when (sort) {
            ChannelSort.NAME -> subs
            ChannelSort.UNWATCHED -> subs.sortedByDescending { it.unwatchedCount }
            ChannelSort.LATEST -> subs.sortedByDescending { it.latestAt ?: 0 }
            ChannelSort.TYPE -> subs.sortedBy { it.subscription.type.ordinal }
        }
    }
    val grouped = remember(sorted) { sorted.groupBy { it.subscription.category?.takeIf { c -> c.isNotBlank() } } }

    ScreenScaffold(
        title = "Canales (${subs.size})",
        actions = {
            IconButton(onClick = { grid = !grid }) {
                Icon(if (grid) Icons.Filled.ViewList else Icons.Filled.GridView, "Cambiar vista")
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Ordenar") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    ChannelSort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label, fontWeight = if (s == sort) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { sort = s; sortMenu = false },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate(Routes.add()) }) { Icon(Icons.Filled.Add, "Añadir canal") }
        },
    ) { padding ->
        if (subs.isEmpty()) {
            EmptyState(
                Icons.Filled.Subscriptions,
                "Sin canales",
                "Pega la URL de un canal de YouTube, Twitch, Dailymotion, Vimeo, Odysee, un podcast o una web con RSS.",
                Modifier.padding(padding),
            ) { Button(onClick = { nav.navigate(Routes.add()) }) { Text("Añadir canal") } }
            return@ScreenScaffold
        }
        LazyVerticalGrid(
            columns = if (grid) GridCells.Adaptive(110.dp) else GridCells.Fixed(1),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
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
                    if (grid) ChannelTile(s) { nav.navigate(Routes.channel(s.subscription.id)) }
                    else ChannelListRow(s) { nav.navigate(Routes.channel(s.subscription.id)) }
                }
            }
        }
    }
}

@Composable
private fun ChannelTile(s: SubscriptionWithCount, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                ChannelAvatar(s.subscription.imageUrl, s.subscription.title, 72.dp)
                if (s.unwatchedCount > 0) {
                    Badge(Modifier.align(Alignment.TopEnd)) { Text(if (s.unwatchedCount > 99) "99+" else "${s.unwatchedCount}") }
                }
                if (s.subscription.lastError != null) {
                    Icon(
                        Icons.Filled.ErrorOutline, "Error al actualizar",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                s.subscription.title, style = MaterialTheme.typography.labelLarge, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            SourceBadge(s.subscription.type)
        }
    }
}

@Composable
private fun ChannelListRow(s: SubscriptionWithCount, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(s.subscription.imageUrl, s.subscription.title, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.subscription.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceBadge(s.subscription.type)
                    Text(
                        "${s.totalCount} episodios" + (s.latestAt?.let { " · último ${relativeTime(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (s.unwatchedCount > 0) Badge { Text("${s.unwatchedCount}") }
        }
    }
}
