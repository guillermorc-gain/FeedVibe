package com.feedvibe.app.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.feedvibe.app.data.backup.DriveAuthRequired
import com.feedvibe.app.data.backup.DriveFile
import com.feedvibe.app.data.prefs.AppSettings
import com.feedvibe.app.data.prefs.BACKUP_INTERVALS
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.relativeTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private sealed interface DriveAction {
    data object Backup : DriveAction
    data object ListFiles : DriveAction
    data class Restore(val file: DriveFile) : DriveAction
    data class Delete(val file: DriveFile) : DriveAction
}

@Composable
fun BackupScreen(nav: NavController, settings: AppSettings) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user by container.auth.user.collectAsStateWithLifecycle()
    val lastBackup by container.settings.lastBackup.collectAsStateWithLifecycle(0L)

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<DriveFile>?>(null) }
    var pending by remember { mutableStateOf<DriveAction?>(null) }
    var confirmRestore by remember { mutableStateOf<DriveFile?>(null) }

    suspend fun perform(action: DriveAction, token: String) {
        val drive = container.drive
        when (action) {
            DriveAction.Backup -> {
                drive.backupAndPrune(token, container.backup.fileName(), container.backup.createJson(), settings.backupKeep)
                container.settings.setLastBackup(System.currentTimeMillis())
                message = "Copia guardada en Google Drive (carpeta FeedVibe)"
                files = drive.list(token)
            }
            DriveAction.ListFiles -> files = drive.list(token)
            is DriveAction.Restore -> {
                val r = container.backup.restoreJson(drive.download(token, action.file.id))
                message = "Restaurados ${r.subscriptions} canales y ${r.states} estados"
                container.feeds.refreshAll()
            }
            is DriveAction.Delete -> {
                drive.delete(token, action.file.id)
                files = drive.list(token)
            }
        }
    }

    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val action = pending
        pending = null
        val token = if (result.resultCode == Activity.RESULT_OK) container.drive.tokenFromIntent(result.data) else null
        if (token == null || action == null) {
            busy = false
            message = "Permiso de Google Drive no concedido"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { perform(action, token) } }.onFailure { message = it.message }
            busy = false
        }
    }

    fun startDrive(action: DriveAction) {
        busy = true
        message = null
        scope.launch {
            try {
                val token = container.drive.accessToken()
                withContext(Dispatchers.IO) { perform(action, token) }
                busy = false
            } catch (e: DriveAuthRequired) {
                pending = action
                authLauncher.launch(IntentSenderRequest.Builder(e.pendingIntent.intentSender).build())
            } catch (e: Exception) {
                message = e.message ?: "Error con Google Drive"
                busy = false
            }
        }
    }

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

    SettingsPage(nav, "Copias de seguridad") {
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }

        SectionTitle("Google Drive")
        SettingsGroup {
            if (user == null) {
                ListItem(
                    headlineContent = { Text("Inicia sesión con Google") },
                    supportingContent = { Text("Desde la pestaña Perfil, para guardar copias en tu Google Drive.") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            } else {
                ListItem(
                    headlineContent = { Text("Última copia") },
                    supportingContent = { Text(if (lastBackup > 0) relativeTime(lastBackup) else "Nunca") },
                    trailingContent = { if (busy) CircularProgressIndicator(Modifier.padding(4.dp)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Button(onClick = { startDrive(DriveAction.Backup) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.CloudUpload, null); Spacer(Modifier.width(6.dp)); Text("Copiar ahora")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { startDrive(DriveAction.ListFiles) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.CloudDownload, null); Spacer(Modifier.width(6.dp)); Text("Restaurar")
                    }
                }
            }
        }

        if (user != null) {
            SectionTitle("Copia automática")
            SettingsGroup {
                BACKUP_INTERVALS.forEach { (days, label) ->
                    RadioRow(label, days, settings.autoBackupDays) { v ->
                        scope.launch { container.settings.update { it.copy(autoBackupDays = v) } }
                    }
                }
                ListItem(
                    headlineContent = { Text("Copias a conservar") },
                    supportingContent = { Text("Se borran automáticamente las más antiguas") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { scope.launch { container.settings.update { it.copy(backupKeep = (it.backupKeep - 1).coerceAtLeast(1)) } } }) { Text("−") }
                            Text("${settings.backupKeep}")
                            TextButton(onClick = { scope.launch { container.settings.update { it.copy(backupKeep = (it.backupKeep + 1).coerceAtMost(50)) } } }) { Text("+") }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }

        files?.let { list ->
            SectionTitle("Copias en Drive (${list.size})")
            SettingsGroup {
                if (list.isEmpty()) {
                    ListItem(headlineContent = { Text("No hay copias todavía") }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
                list.forEach { f ->
                    ListItem(
                        headlineContent = { Text(formatDriveDate(f.createdTime)) },
                        supportingContent = { Text("${f.size / 1024} KB") },
                        trailingContent = {
                            IconButton(onClick = { startDrive(DriveAction.Delete(f)) }) { Icon(Icons.Filled.Delete, "Borrar") }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { confirmRestore = f },
                    )
                }
            }
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

    confirmRestore?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restaurar copia") },
            text = { Text("Se añadirán los canales de la copia del ${formatDriveDate(f.createdTime)} y se combinará lo que has visto (gana siempre lo más reciente).") },
            confirmButton = { TextButton(onClick = { confirmRestore = null; startDrive(DriveAction.Restore(f)) }) { Text("Restaurar") } },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancelar") } },
        )
    }
}

private fun formatDriveDate(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
}.getOrDefault(iso)

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
