package com.feedvibe.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.feedvibe.app.data.repo.FeedRepository
import com.feedvibe.app.data.sources.PodcastSearch
import com.feedvibe.app.data.sources.SourceType
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.Routes
import com.feedvibe.app.ui.components.ChannelAvatar
import com.feedvibe.app.ui.components.ScreenScaffold
import com.feedvibe.app.ui.components.SourceBadge
import com.feedvibe.app.ui.relativeTime
import kotlinx.coroutines.launch

private val examples = listOf(
    SourceType.YOUTUBE to "youtube.com/@canal  ·  @canal  ·  enlace de un vídeo o lista",
    SourceType.TWITCH to "twitch.tv/usuario",
    SourceType.DAILYMOTION to "dailymotion.com/usuario",
    SourceType.VIMEO to "vimeo.com/usuario",
    SourceType.ODYSEE to "odysee.com/@canal",
    SourceType.PODCAST to "Enlace de Apple Podcasts o feed RSS del podcast",
    SourceType.RSS to "Cualquier web o blog (se busca su RSS automáticamente)",
)

@Composable
fun AddFeedScreen(nav: NavController, initialUrl: String?) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(0) } // 0 = URL, 1 = buscar podcast
    var input by rememberSaveable { mutableStateOf(initialUrl.orEmpty()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<FeedRepository.Preview?>(null) }
    var markOld by rememberSaveable { mutableStateOf(true) }
    var category by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PodcastSearch.Result>>(emptyList()) }

    fun load(text: String = input) {
        if (text.isBlank()) return
        loading = true; error = null; preview = null
        scope.launch {
            runCatching { container.feeds.preview(text) }
                .onSuccess { preview = it }
                .onFailure { error = it.message ?: "No se pudo cargar" }
            loading = false
        }
    }

    fun search() {
        if (input.isBlank()) return
        loading = true; error = null
        scope.launch {
            runCatching { PodcastSearch.search(input) }
                .onSuccess { results = it; if (it.isEmpty()) error = "Sin resultados" }
                .onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(initialUrl) { if (!initialUrl.isNullOrBlank()) load(initialUrl) }

    ScreenScaffold(title = "Añadir canal", onBack = { nav.popBackStack() }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("URL o canal", "Buscar podcast").forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = mode == i,
                            onClick = { mode = i; error = null; preview = null },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    label = { Text(if (mode == 0) "URL, @canal o web" else "Nombre del podcast") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (mode == 0) KeyboardType.Uri else KeyboardType.Text,
                        imeAction = if (mode == 0) ImeAction.Go else ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onGo = { load() }, onSearch = { search() }),
                    trailingIcon = {
                        if (mode == 0) {
                            IconButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { input = it; load(it) }
                            }) { Icon(Icons.Filled.ContentPaste, "Pegar") }
                        } else {
                            IconButton(onClick = { search() }) { Icon(Icons.Filled.Search, "Buscar") }
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { if (mode == 0) load() else search() },
                    enabled = !loading && input.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (mode == 0) "Buscar canal" else "Buscar") }
                Spacer(Modifier.height(12.dp))
                if (loading) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            }

            preview?.let { p ->
                item {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ChannelAvatar(p.feed.imageUrl, p.feed.title, 64.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.feed.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    SourceBadge(p.type)
                                    Text("${p.feed.episodes.size} episodios encontrados", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            p.feed.episodes.take(3).forEach { e ->
                                Text(
                                    "• ${e.title}  (${relativeTime(e.publishedAt)})",
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            if (p.alreadySubscribed) {
                                Text("Ya sigues este canal", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { markOld = !markOld }) {
                                    Checkbox(checked = markOld, onCheckedChange = { markOld = it })
                                    Text("Marcar como vistos los episodios ya publicados")
                                }
                                OutlinedTextField(
                                    value = category, onValueChange = { category = it }, singleLine = true,
                                    label = { Text("Categoría (opcional)") }, modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        loading = true
                                        scope.launch {
                                            runCatching { container.feeds.subscribe(p, markOld, category) }
                                                .onSuccess { sub ->
                                                    nav.popBackStack()
                                                    nav.navigate(Routes.channel(sub.id))
                                                }
                                                .onFailure { error = it.message ?: "No se pudo suscribir" }
                                            loading = false
                                        }
                                    },
                                    enabled = !loading,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Suscribirse") }
                            }
                        }
                    }
                }
            }

            if (mode == 1) {
                items(results, key = { it.feedUrl }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { mode = 0; input = r.feedUrl; load(r.feedUrl) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChannelAvatar(r.imageUrl, r.title, 52.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(r.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Filled.Podcasts, null, Modifier.size(20.dp))
                    }
                }
            }

            if (preview == null && mode == 0) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Puedes añadir", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                }
                items(examples) { (type, text) ->
                    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        SourceBadge(type, Modifier.width(92.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(text, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Consejo: desde YouTube, Twitch o el navegador usa «Compartir» → FeedVibe para añadir el canal directamente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}
