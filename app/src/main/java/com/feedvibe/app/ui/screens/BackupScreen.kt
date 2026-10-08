package com.feedvibe.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.feedvibe.app.data.prefs.AppSettings
import com.feedvibe.app.ui.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackupScreen(nav: NavController, settings: AppSettings) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    // ---------- Archivo local ----------
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = container.backup.createJson()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
            }.onSuccess { message = "Copia exportada" }.onFailure { message = it.message }
        }
    }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
                container.backup.restoreJson(text ?: error("Archivo vacío"))
            }.onSuccess {
                message = "Restaurados ${it.subscriptions} canales y ${it.states} estados"
                container.appScope.launch { container.feeds.refreshAll() }
            }.onFailure { message = "No es una copia válida de FeedVibe: ${it.message}" }
        }
    }
    val exportOpml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val xml = container.backup.exportOpml()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(xml.toByteArray()) } }
            }.onSuccess { message = "OPML exportado" }.onFailure { message = it.message }
        }
    }
    val importOpml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
                container.backup.importOpml(text ?: error("Archivo vacío"))
            }.onSuccess {
                message = "Importados ${it.added.size} canales"
                container.appScope.launch { container.feeds.refreshAll() }
            }.onFailure { message = "OPML no válido: ${it.message}" }
        }
    }

    // Todo se sincroniza al momento con tu cuenta de Google: aquí solo quedan los archivos.
    SettingsPage(nav, "Copias de seguridad") {
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }

        SectionTitle("Archivo en el teléfono")
        SettingsGroup {
            ActionRow(Icons.Filled.FileUpload, "Exportar copia", "Guarda un archivo .json con todo") { exportJson.launch(container.backup.fileName()) }
            ActionRow(Icons.Filled.FileDownload, "Importar copia", "Desde un archivo .json de FeedVibe") { importJson.launch(arrayOf("application/json", "*/*")) }
        }

        SectionTitle("OPML (otras apps de podcasts/RSS)")
        SettingsGroup {
            ActionRow(Icons.Filled.RssFeed, "Exportar OPML", "Lista de canales para Podcast Addict, Feedly…") { exportOpml.launch("feedvibe.opml") }
            ActionRow(Icons.Filled.RssFeed, "Importar OPML", "Trae tus suscripciones de Podcast Addict u otra app") { nav.navigate(com.feedvibe.app.ui.Routes.IMPORT_OPML) }
        }
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
