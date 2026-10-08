package com.feedvibe.app.ui.screens

import com.feedvibe.app.ui.components.BrandTitle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.feedvibe.app.BuildConfig
import com.feedvibe.app.R
import com.feedvibe.app.data.prefs.AppSettings
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.openUrl
import com.feedvibe.app.update.UpdateState
import kotlinx.coroutines.launch

@Composable
fun AboutScreen(nav: NavController, settings: AppSettings) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by container.updater.state.collectAsStateWithLifecycle()
    val icon = androidx.compose.runtime.remember {
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(256, 256)?.asImageBitmap()
    }

    SettingsPage(nav, "Actualizaciones") {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            icon?.let { Image(it, null, Modifier.size(96.dp).clip(RoundedCornerShape(24.dp))) }
            Spacer(Modifier.height(12.dp))
            BrandTitle(fontSize = 30.sp, showIcon = false)
            Text("Versión ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            when (val s = state) {
                UpdateState.Checking -> CircularProgressIndicator()
                UpdateState.UpToDate -> Text("✔ Tienes la última versión", color = MaterialTheme.colorScheme.primary)
                is UpdateState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                is UpdateState.Downloading -> LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                else -> Unit
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { scope.launch { container.updater.check() } },
                enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
            ) { Text("Buscar actualizaciones") }
        }
        SettingsGroup {
            SwitchRow("Buscar actualizaciones automáticamente", "Cada vez que abres la app; se instalan sin salir de ella", settings.autoUpdateCheck) { on ->
                scope.launch { container.settings.update { it.copy(autoUpdateCheck = on) } }
            }
        }
    }
}
