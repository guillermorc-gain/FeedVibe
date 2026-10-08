package com.feedvibe.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.feedvibe.app.data.prefs.AppSettings

/** Ajustes de la radio (Perfil → Radio, en modo radio). */
@Composable
fun RadioSettingsScreen(nav: NavController, settings: AppSettings) {
    val context = LocalContext.current
    val update = rememberUpdate()
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        // Permiso permanente para escribir ahí las grabaciones.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        update { s -> s.copy(radioRecordFolder = uri.toString()) }
    }

    SettingsPage(nav, "Radio") {
        SectionTitle("Calidad al escuchar")
        SettingsGroup {
            RadioRow("Máxima calidad", "MAX", settings.radioQuality) { update { s -> s.copy(radioQuality = it) } }
            RadioRow("Ahorro de datos", "SAVER", settings.radioQuality) { update { s -> s.copy(radioQuality = it) } }
        }
        Text(
            "Si la emisora emite en varias calidades se elige la mejor (o la más ligera con ahorro de datos). La calidad de cada emisora se ve en la lista y en el reproductor.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )

        SectionTitle("Grabaciones")
        SettingsGroup {
            ListItem(
                headlineContent = { Text("Carpeta de las grabaciones") },
                supportingContent = {
                    Text(
                        settings.radioRecordFolder.takeIf { it.isNotBlank() }?.let { Uri.decode(it).substringAfterLast(':').ifBlank { "Elegida" } }
                            ?: "Música/FeedVibe (toca para elegir otra)"
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { folder.launch(null) },
            )
            SwitchRow(
                "Grabar y descargar solo con Wi‑Fi",
                "Con datos móviles no se graba ni se descargan programas",
                settings.radioRecordWifiOnly,
            ) { update { s -> s.copy(radioRecordWifiOnly = it) } }
            ListItem(
                headlineContent = { Text("Formato") },
                supportingContent = { Text("FLAC, con el nombre de la emisora, el del programa y su logo") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}
