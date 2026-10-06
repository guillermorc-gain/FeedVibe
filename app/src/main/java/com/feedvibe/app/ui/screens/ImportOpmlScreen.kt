package com.feedvibe.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.Routes
import com.feedvibe.app.ui.navigateTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Importar todas las suscripciones de Podcast Addict (u otra app) desde un archivo OPML. */
@Composable
fun ImportOpmlScreen(nav: NavController) {
    val container = LocalContainer.current
    val context = LocalContext.current
    var markWatched by rememberSaveable { mutableStateOf(true) }
    var stage by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = stage != null

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        error = null
        result = null
        stage = "Leyendo el archivo…"
        val mark = markWatched
        // En appScope: la importación sigue aunque salgas de la pantalla.
        container.appScope.launch(Dispatchers.Main) {
            try {
                val xml = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                } ?: error("No se pudo abrir el archivo")
                stage = "Añadiendo canales…"
                val r = container.backup.importOpml(xml) { done, total -> progress = done.toFloat() / total; stage = "Añadiendo canales… $done de $total" }
                if (r.added.isNotEmpty()) {
                    stage = "Descargando episodios de ${r.added.size} canales…"
                    progress = 0f
                    container.feeds.refreshAll(r.added.toSet())
                    if (mark) {
                        stage = "Marcando como vistos los episodios ya publicados…"
                        container.feeds.markSubscriptionsWatched(r.added)
                    }
                }
                result = buildString {
                    append("✔ ${r.added.size} canales añadidos")
                    if (r.alreadyHad > 0) append("\n${r.alreadyHad} ya los tenías")
                    if (r.failed.isNotEmpty()) append("\n${r.failed.size} no se pudieron añadir: ${r.failed.take(5).joinToString()}" + if (r.failed.size > 5) "…" else "")
                }
            } catch (e: Exception) {
                error = e.message ?: "No es un archivo OPML válido"
            } finally {
                stage = null
            }
        }
    }

    SettingsPage(nav, "Importar suscripciones") {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Trae todos tus canales de golpe desde Podcast Addict (o cualquier app que exporte OPML).", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Text("1. En Podcast Addict", fontWeight = FontWeight.SemiBold)
            Text(
                "Ajustes ⚙ → Copia de seguridad / Restaurar → «Exportar OPML». Guarda el archivo (por ejemplo en Descargas o en Drive).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text("2. Aquí", fontWeight = FontWeight.SemiBold)
            Text(
                "Pulsa «Elegir archivo OPML» y selecciónalo. Se añaden canales de YouTube, podcasts y feeds RSS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = !busy) { markWatched = !markWatched }) {
                Checkbox(checked = markWatched, onCheckedChange = { markWatched = it }, enabled = !busy)
                Column {
                    Text("Marcar como vistos los episodios ya publicados")
                    Text(
                        "Recomendado si ya estabas al día en Podcast Addict: solo verás como nuevo lo que salga a partir de ahora.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.FileOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("Elegir archivo OPML")
            }
            stage?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                if (progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            result?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { (nav as? androidx.navigation.NavHostController)?.navigateTab(Routes.CHANNELS) ?: nav.navigate(Routes.CHANNELS) }) { Text("Ver mis canales") }
            }
        }
    }
}
