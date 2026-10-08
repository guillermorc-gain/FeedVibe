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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FiberManualRecord
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
import kotlinx.coroutines.launch

/** Qué lista se está viendo. */
private sealed interface RadioList {
    data object Popular : RadioList
    data class Zone(val title: String, val places: List<com.feedvibe.app.data.radio.Place>) : RadioList
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
    var genres by remember { mutableStateOf<List<Category>>(emptyList()) }
    var zoneMenu by remember { mutableStateOf(false) }
    var genreMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var playerSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
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
                is RadioList.Zone -> TuneIn.local(l.places)
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
                        label = { Text(z?.title ?: "Zona") },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                    )
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

    if (zoneMenu) ZonePicker(onDismiss = { zoneMenu = false }) { title, places -> zoneMenu = false; list = RadioList.Zone(title, places) }

    val playing = now
    if (playerSheet && playing != null) {
        val n = playing
        ModalBottomSheet(onDismissRequest = { playerSheet = false }) {
            PlayerSheet(n, favorite = n.station.id in favIds, onToggle = { radio.toggle() }, onFavorite = { scope.launchToggle(container, n.station) })
        }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchToggle(container: com.feedvibe.app.AppContainer, s: Station) =
    launch { container.settings.toggleFavoriteStation(s) }

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

/** Escuchando: logo, canción, calidad, grabación y la programación de la emisora. */
@Composable
private fun PlayerSheet(n: NowPlaying, favorite: Boolean, onToggle: () -> Unit, onFavorite: () -> Unit) {
    val container = LocalContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val recording by com.feedvibe.app.radio.RecordService.state.collectAsStateWithLifecycle()
    val scheduled by container.settings.radioSchedules.collectAsStateWithLifecycle(emptyList())
    var schedule by remember(n.station.id) { mutableStateOf<List<ScheduleItem>?>(null) }
    var confirm by remember { mutableStateOf<com.feedvibe.app.radio.RecordJob?>(null) }
    var byTime by remember { mutableStateOf(false) }
    var episodesOf by remember { mutableStateOf<ScheduleItem?>(null) }
    LaunchedEffect(n.station.id) { schedule = runCatching { TuneIn.schedule(n.station.id) }.getOrDefault(emptyList()) }
    val hour = remember { SimpleDateFormat("HH:mm", Locale("es")) }
    val nowMs = System.currentTimeMillis()
    val format = com.feedvibe.app.radio.RecordFormat.of(settings?.radioRecordFormat ?: "FLAC")
    val kbps = n.stream?.bitrate ?: n.station.bitrate
    fun estimate(seconds: Long) = com.feedvibe.app.radio.formatSize(format.estimateBytes(kbps, seconds))
    val recordingHere = recording?.job?.stationId == n.station.id

    fun job(program: String, start: Long, end: Long) = com.feedvibe.app.radio.RecordJob(
        id = System.currentTimeMillis(), stationId = n.station.id, stationName = n.station.name,
        image = n.station.image, program = program, startAtMs = start, endAtMs = end,
    )

    LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            StationLogo(n.station.image, 160)
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
                // Grabar ya (hasta pararlo) o parar la grabación.
                IconButton(onClick = {
                    if (recordingHere) com.feedvibe.app.radio.RecordService.stop(context)
                    else com.feedvibe.app.radio.RecordService.start(context, job("", 0, 0))
                }) {
                    Icon(
                        if (recordingHere) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        if (recordingHere) "Parar la grabación" else "Grabar",
                        tint = androidx.compose.ui.graphics.Color(0xFFE53935),
                    )
                }
            }
            recording?.takeIf { recordingHere }?.let { r ->
                val secs = (System.currentTimeMillis() - r.startedMs) / 1000
                Text(
                    "● ${r.status} · " + String.format(Locale("es"), "%d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60) + " · " + com.feedvibe.app.radio.formatSize(r.bytes),
                    color = androidx.compose.ui.graphics.Color(0xFFE53935),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                "Grabar en ${format.label}: 1 hora ≈ ${estimate(3600)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.TextButton(onClick = { byTime = true }) { Text("Grabar por horario…") }
            if (scheduled.isNotEmpty()) {
                Text(
                    "Grabaciones programadas",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
                scheduled.sortedBy { it.startAtMs }.forEach { j ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${j.stationName}${if (j.program.isNotBlank()) " · ${j.program}" else ""}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                SimpleDateFormat("EEE d MMM, HH:mm", Locale("es")).format(Date(j.startAtMs)) + " – " + hour.format(Date(j.endAtMs)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            com.feedvibe.app.radio.RecordScheduler.cancel(context, j)
                            scope.launch { container.settings.editRadioSchedules { l -> l.filter { it.id != j.id } } }
                        }) { Icon(Icons.Filled.Close, "Cancelar") }
                    }
                }
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
                Text("Esta emisora no publica su programación. Puedes grabar con el botón rojo o por horario.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
            else -> items(list.filter { it.endMs > nowMs }) { p ->
                val live = nowMs in p.startMs until p.endMs
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        hour.format(Date(p.startMs)),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(56.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(p.title, fontWeight = if (live) FontWeight.Bold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (live) "En directo · " else "") + "hasta las " + hour.format(Date(p.endMs)) + " · ≈ " + estimate(p.durationSec),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (p.showId != null) IconButton(onClick = { episodesOf = p }) {
                        Icon(Icons.Filled.Download, "Episodios a la carta")
                    }
                    IconButton(onClick = { confirm = job(p.title, if (live) 0 else p.startMs, p.endMs) }) {
                        Icon(Icons.Filled.FiberManualRecord, "Grabar este programa", tint = androidx.compose.ui.graphics.Color(0xFFE53935))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    fun program(j: com.feedvibe.app.radio.RecordJob) {
        if (j.startAtMs == 0L) {
            com.feedvibe.app.radio.RecordService.start(context, j)
        } else {
            com.feedvibe.app.radio.RecordScheduler.schedule(context, j)
            scope.launch { container.settings.editRadioSchedules { it + j } }
        }
    }

    confirm?.let { j ->
        val secs = ((j.endAtMs - (if (j.startAtMs == 0L) System.currentTimeMillis() else j.startAtMs)) / 1000).coerceAtLeast(60)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Grabar «${j.program}»") },
            text = {
                Text(
                    (if (j.startAtMs == 0L) "Empieza ahora" else "De ${hour.format(Date(j.startAtMs))}") + " hasta las ${hour.format(Date(j.endAtMs))}.\n" +
                        "Formato ${format.label}: pesará unos ${estimate(secs)}." +
                        if (format != com.feedvibe.app.radio.RecordFormat.ORIGINAL) "\nEn formato original serían unos ${com.feedvibe.app.radio.formatSize(com.feedvibe.app.radio.RecordFormat.ORIGINAL.estimateBytes(kbps, secs))}." else ""
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { program(j); confirm = null }) { Text("Grabar") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
        )
    }

    if (byTime) RecordByTimeDialog(
        onDismiss = { byTime = false },
        estimate = { secs -> estimate(secs) },
        onConfirm = { start, end -> byTime = false; confirm = job("", start, end) },
    )

    episodesOf?.let { p -> EpisodesDialog(p, onDismiss = { episodesOf = null }) }
}

/** Grabar por horario: hora de inicio y duración. */
@Composable
private fun RecordByTimeDialog(onDismiss: () -> Unit, estimate: (Long) -> String, onConfirm: (Long, Long) -> Unit) {
    val cal = remember { java.util.Calendar.getInstance() }
    var startH by remember { mutableStateOf(cal.get(java.util.Calendar.HOUR_OF_DAY)) }
    var startM by remember { mutableStateOf((cal.get(java.util.Calendar.MINUTE) / 5 + 1) * 5 % 60) }
    var minutes by remember { mutableStateOf(60) }
    fun startMs(): Long {
        val c = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, startH); set(java.util.Calendar.MINUTE, startM)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        // Si la hora ya ha pasado hoy, es mañana.
        if (c.timeInMillis < System.currentTimeMillis() - 60_000) c.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Grabar por horario") },
        text = {
            Column {
                Text("Empieza a las", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { startH = (startH + 23) % 24 }) { Text("−") }
                    Text(String.format(Locale("es"), "%02d", startH), style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = { startH = (startH + 1) % 24 }) { Text("+") }
                    Text(":", style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = { startM = (startM + 55) % 60 }) { Text("−") }
                    Text(String.format(Locale("es"), "%02d", startM), style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = { startM = (startM + 5) % 60 }) { Text("+") }
                }
                Spacer(Modifier.height(8.dp))
                Text("Duración", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30, 60, 90, 120, 180, 240).forEach { m ->
                        FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(if (m % 60 == 0) "${m / 60} h" else "$m min") })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Pesará unos ${estimate(minutes * 60L)}", color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { val s = startMs(); onConfirm(s, s + minutes * 60_000L) }) { Text("Programar") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Episodios a la carta de un programa, para descargar. */
@Composable
private fun EpisodesDialog(p: ScheduleItem, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var episodes by remember { mutableStateOf<List<com.feedvibe.app.data.radio.Episode>?>(null) }
    var done by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(p.showId) { episodes = runCatching { TuneIn.episodes(p.showId!!) }.getOrDefault(emptyList()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.title) },
        text = {
            val list = episodes
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text("Este programa no tiene episodios para descargar.")
                else -> LazyColumn(Modifier.height(380.dp)) {
                    items(list, key = { it.id }) { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    listOf(e.date, if (e.durationSec > 0) "${e.durationSec / 60} min · ≈ ${com.feedvibe.app.radio.formatSize(e.durationSec * 16_000)}" else "")
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(enabled = e.id !in done, onClick = {
                                scope.launch {
                                    runCatching {
                                        val wifiOnly = container.settings.current().radioRecordWifiOnly
                                        com.feedvibe.app.radio.EpisodeDownloader.download(context, e, p.title, wifiOnly)
                                    }.onSuccess {
                                        done = done + e.id
                                        android.widget.Toast.makeText(context, "Descargando en Música/FeedVibe", android.widget.Toast.LENGTH_SHORT).show()
                                    }.onFailure {
                                        android.widget.Toast.makeText(context, it.message ?: "No se pudo descargar", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }) { Icon(if (e.id in done) Icons.Filled.DownloadDone else Icons.Filled.Download, "Descargar") }
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

/** Elegir zona: comunidad autónoma entera o una de sus islas o provincias. */
@Composable
private fun ZonePicker(onDismiss: () -> Unit, onPick: (String, List<com.feedvibe.app.data.radio.Place>) -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zona") },
        text = {
            LazyColumn(Modifier.height(440.dp)) {
                com.feedvibe.app.data.radio.SPAIN_REGIONS.forEach { r ->
                    item(key = r.name) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (r.places.size == 1) onPick(r.name, r.places) else open = if (open == r.name) null else r.name
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(r.name, Modifier.weight(1f), fontWeight = if (open == r.name) FontWeight.Bold else FontWeight.Normal)
                            if (r.places.size > 1) Icon(Icons.Filled.ArrowDropDown, null)
                        }
                    }
                    if (open == r.name) {
                        item(key = r.name + "/todo") {
                            Text(
                                "Toda ${r.name}",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().clickable { onPick(r.name, r.places) }.padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
                            )
                        }
                        r.places.forEach { p ->
                            item(key = r.name + "/" + p.name) {
                                Text(
                                    p.name,
                                    modifier = Modifier.fillMaxWidth().clickable { onPick(p.name, listOf(p)) }.padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
