package com.feedvibe.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.feedvibe.app.data.prefs.AppSettings
import com.feedvibe.app.data.radio.Category
import com.feedvibe.app.data.radio.ScheduleItem
import com.feedvibe.app.data.radio.Station
import com.feedvibe.app.data.radio.TuneIn
import com.feedvibe.app.radio.NowPlaying
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.Routes
import com.feedvibe.app.ui.components.EmptyState
import com.feedvibe.app.ui.components.ScreenScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Qué lista se está viendo. */
private sealed interface RadioList {
    data object Popular : RadioList
    data class Zone(val c: Category) : RadioList
    data class Genre(val c: Category) : RadioList
    data object Favorites : RadioList
    data object Recent : RadioList
    data class Search(val q: String) : RadioList
}

/** Pestaña Radio (modo radio): emisoras por zona y estilo, búsqueda, favoritas y reproductor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen(nav: NavController, settings: AppSettings) {
    val container = LocalContainer.current
    val radio = container.radio
    val now by radio.now.collectAsStateWithLifecycle()
    val favorites by container.settings.radioFavorites.collectAsStateWithLifecycle(emptyList())
    val recents by container.settings.radioRecents.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    var list by remember { mutableStateOf<RadioList>(RadioList.Popular) }
    var stations by remember { mutableStateOf<List<Station>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var zones by remember { mutableStateOf<List<Category>>(emptyList()) }
    var genres by remember { mutableStateOf<List<Category>>(emptyList()) }
    var zoneMenu by remember { mutableStateOf(false) }
    var genreMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var playerSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { zones = TuneIn.zones() }
        runCatching { genres = TuneIn.genres() }
    }
    LaunchedEffect(list) {
        val l = list
        if (l == RadioList.Favorites || l == RadioList.Recent) return@LaunchedEffect
        loading = true
        error = null
        runCatching {
            when (l) {
                RadioList.Popular -> TuneIn.popular()
                is RadioList.Zone -> TuneIn.browse(l.c.url)
                is RadioList.Genre -> TuneIn.browse(l.c.url)
                is RadioList.Search -> TuneIn.search(l.q).filter { !it.isShow }
                else -> emptyList()
            }
        }.onSuccess { stations = it }.onFailure { error = it.message ?: "No se pudo cargar la lista"; stations = emptyList() }
        loading = false
    }
    val shown = when (list) {
        RadioList.Favorites -> favorites
        RadioList.Recent -> recents
        else -> stations
    }
    val favIds = favorites.map { it.id }.toSet()

    ScreenScaffold(title = "FeedVibe", brand = true) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Los botones van en esta línea para dejar despejado el nombre de la app.
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (shown.isNotEmpty()) "Radio (${shown.size})" else "Radio",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más opciones") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Ajustes de radio") },
                            leadingIcon = { Icon(Icons.Filled.Settings, null) },
                            onClick = { menu = false; nav.navigate(Routes.RADIO_SETTINGS) },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Buscar emisora…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = ""; list = RadioList.Popular }) { Icon(Icons.Filled.Close, "Borrar") }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) list = RadioList.Search(query.trim()) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = list == RadioList.Popular, onClick = { list = RadioList.Popular }, label = { Text("Populares") })
                Box {
                    val z = list as? RadioList.Zone
                    FilterChip(
                        selected = z != null,
                        onClick = { zoneMenu = true },
                        label = { Text(z?.c?.title ?: "Zona") },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                    )
                    DropdownMenu(expanded = zoneMenu, onDismissRequest = { zoneMenu = false }) {
                        if (zones.isEmpty()) DropdownMenuItem(text = { Text("Cargando…") }, onClick = {})
                        zones.forEach { c -> DropdownMenuItem(text = { Text(c.title) }, onClick = { zoneMenu = false; list = RadioList.Zone(c) }) }
                    }
                }
                Box {
                    val g = list as? RadioList.Genre
                    FilterChip(
                        selected = g != null,
                        onClick = { genreMenu = true },
                        label = { Text(g?.c?.title ?: "Estilo") },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                    )
                    DropdownMenu(expanded = genreMenu, onDismissRequest = { genreMenu = false }) {
                        if (genres.isEmpty()) DropdownMenuItem(text = { Text("Cargando…") }, onClick = {})
                        genres.forEach { c -> DropdownMenuItem(text = { Text(c.title) }, onClick = { genreMenu = false; list = RadioList.Genre(c) }) }
                    }
                }
                FilterChip(selected = list == RadioList.Favorites, onClick = { list = RadioList.Favorites }, label = { Text("★ Favoritas (${favorites.size})") })
                FilterChip(selected = list == RadioList.Recent, onClick = { list = RadioList.Recent }, label = { Text("Recientes") })
            }
            Box(Modifier.weight(1f)) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    error != null -> EmptyState(Icons.Filled.Radio, "No se pudo cargar", error!!)
                    shown.isEmpty() -> EmptyState(
                        Icons.Filled.Radio,
                        if (list == RadioList.Favorites) "Sin favoritas" else "Nada por aquí",
                        if (list == RadioList.Favorites) "Toca la estrella de una emisora para guardarla aquí." else "Prueba con otra zona, estilo o búsqueda.",
                    )
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                        items(shown, key = { it.id }) { s ->
                            StationRow(
                                s,
                                favorite = s.id in favIds,
                                playing = now?.station?.id == s.id,
                                onClick = { radio.play(s) },
                                onFavorite = { scope.launchToggle(container, s) },
                            )
                        }
                    }
                }
            }
            now?.let { n -> MiniPlayer(n, onOpen = { playerSheet = true }, onToggle = { radio.toggle() }, onStop = { radio.stop() }) }
        }
    }

    val playing = now
    if (playerSheet && playing != null) {
        val n = playing
        ModalBottomSheet(onDismissRequest = { playerSheet = false }) {
            PlayerSheet(n, favorite = n.station.id in favIds, onToggle = { radio.toggle() }, onFavorite = { scope.launchToggle(container, n.station) })
        }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchToggle(container: com.feedvibe.app.AppContainer, s: Station) =
    kotlinx.coroutines.launch { container.settings.toggleFavoriteStation(s) }

private fun Station.quality(): String = buildString {
    if (bitrate > 0) append("$bitrate kbps")
    if (format.isNotBlank()) {
        if (isNotEmpty()) append(" · ")
        append(format.uppercase())
    }
}

@Composable
private fun StationLogo(url: String?, size: Int) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 5).dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Radio, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun StationRow(s: Station, favorite: Boolean, playing: Boolean, onClick: () -> Unit, onFavorite: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationLogo(s.image, 52)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOf(s.subtitle, s.quality()).filter { it.isNotBlank() }.joinToString(" · ")
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onFavorite) {
            Icon(
                if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                if (favorite) "Quitar de favoritas" else "Añadir a favoritas",
                tint = if (favorite) androidx.compose.ui.graphics.Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MiniPlayer(n: NowPlaying, onOpen: () -> Unit, onToggle: () -> Unit, onStop: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(8.dp).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StationLogo(n.station.image, 44)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(n.station.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    n.error ?: n.track ?: listOfNotNull(
                        if (n.loading) "Conectando…" else "En directo",
                        n.stream?.let { "${it.bitrate} kbps ${it.format.uppercase()}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (n.loading) CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
            IconButton(onClick = onToggle) { Icon(if (n.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (n.playing) "Pausa" else "Reproducir") }
            IconButton(onClick = onStop) { Icon(Icons.Filled.Stop, "Parar") }
        }
    }
}

/** Escuchando: logo, canción, calidad y la programación de la emisora. */
@Composable
private fun PlayerSheet(n: NowPlaying, favorite: Boolean, onToggle: () -> Unit, onFavorite: () -> Unit) {
    var schedule by remember(n.station.id) { mutableStateOf<List<ScheduleItem>?>(null) }
    LaunchedEffect(n.station.id) { schedule = runCatching { TuneIn.schedule(n.station.id) }.getOrDefault(emptyList()) }
    val hour = remember { SimpleDateFormat("HH:mm", Locale("es")) }
    val nowMs = System.currentTimeMillis()

    LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            StationLogo(n.station.image, 180)
            Spacer(Modifier.height(12.dp))
            Text(n.station.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            n.track?.let { Text("Ahora: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp)) }
            n.stream?.let {
                Text(
                    "Calidad: ${it.bitrate} kbps · ${it.format.uppercase()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            n.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                IconButton(onClick = onFavorite) {
                    Icon(if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder, "Favorita", tint = if (favorite) androidx.compose.ui.graphics.Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(64.dp)) {
                    Icon(if (n.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, Modifier.size(36.dp))
                }
                Spacer(Modifier.width(48.dp))
            }
            Text(
                "Programación",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        val list = schedule
        when {
            list == null -> item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            list.isEmpty() -> item {
                Text("Esta emisora no publica su programación.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
            else -> items(list.filter { it.endMs > nowMs - 3_600_000 }) { p ->
                val live = nowMs in p.startMs until p.endMs
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        hour.format(Date(p.startMs)),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(56.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(p.title, fontWeight = if (live) FontWeight.Bold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (live) "En directo · " else "") + "hasta las " + hour.format(Date(p.endMs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
