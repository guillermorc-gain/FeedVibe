package com.feedvibe.app.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.feedvibe.app.BuildConfig
import com.feedvibe.app.data.sources.YouTubeApi
import com.feedvibe.app.ui.openUrl
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.feedvibe.app.data.prefs.AccentColor
import com.feedvibe.app.data.prefs.AppSettings
import com.feedvibe.app.data.prefs.ListStyle
import com.feedvibe.app.data.prefs.OpenMode
import com.feedvibe.app.data.prefs.SYNC_INTERVALS
import com.feedvibe.app.data.prefs.ThemeMode
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.components.ChannelAvatar
import com.feedvibe.app.ui.components.ScreenScaffold
import com.feedvibe.app.ui.relativeTime
import com.feedvibe.app.ui.theme.accentScheme
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Helpers

@Composable
fun SettingsPage(nav: NavController, title: String, content: @Composable () -> Unit) {
    ScreenScaffold(title = title, onBack = { nav.popBackStack() }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            content()
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) { { Text(subtitle) } } else null,
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
    )
}

@Composable
fun <T> RadioRow(label: String, value: T, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = value == selected, onClick = { onSelect(value) }, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}

@Composable
private fun rememberUpdate(): ((AppSettings) -> AppSettings) -> Unit {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    return { t -> scope.launch { container.settings.update(t) } }
}

// ---------------------------------------------------------------- Apariencia

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(nav: NavController, settings: AppSettings) {
    val update = rememberUpdate()
    SettingsPage(nav, "Apariencia") {
        SectionTitle("Tema")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeCard(ThemeMode.SYSTEM, Icons.Filled.PhoneAndroid, settings, Modifier.weight(1f)) { update { s -> s.copy(themeMode = it) } }
            ThemeCard(ThemeMode.LIGHT, Icons.Filled.LightMode, settings, Modifier.weight(1f)) { update { s -> s.copy(themeMode = it) } }
            ThemeCard(ThemeMode.DARK, Icons.Filled.DarkMode, settings, Modifier.weight(1f)) { update { s -> s.copy(themeMode = it) } }
        }
        Spacer(Modifier.height(8.dp))
        SettingsGroup {
            SwitchRow("Negro puro (AMOLED)", "Fondo totalmente negro en modo oscuro; ahorra batería", settings.amoled) {
                update { s -> s.copy(amoled = it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow("Colores del fondo de pantalla", "Material You: usa los colores de tu fondo", settings.dynamicColor) {
                    update { s -> s.copy(dynamicColor = it) }
                }
            }
        }

        SectionTitle("Color principal")
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccentColor.entries.forEach { accent ->
                val selected = accent == settings.accent && !settings.dynamicColor
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color(accent.argb))
                            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { update { s -> s.copy(accent = accent, dynamicColor = false) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Filled.Check, null, tint = Color.White)
                    }
                    Text(accent.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        SectionTitle("Lista de episodios")
        SettingsGroup {
            ListStyle.entries.forEach { st ->
                RadioRow(
                    st.label + if (st == ListStyle.CARDS) " (miniaturas grandes)" else " (más episodios en pantalla)",
                    st, settings.listStyle,
                ) { update { s -> s.copy(listStyle = it) } }
            }
        }
    }
}

/** Tarjeta con una mini vista previa del tema. */
@Composable
private fun ThemeCard(mode: ThemeMode, icon: ImageVector, settings: AppSettings, modifier: Modifier, onSelect: (ThemeMode) -> Unit) {
    val selected = settings.themeMode == mode
    val previewDark = mode == ThemeMode.DARK
    val scheme = accentScheme(Color(settings.accent.argb), previewDark, settings.amoled)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                MaterialTheme.shapes.medium,
            )
            .clickable { onSelect(mode) },
    ) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (mode == ThemeMode.SYSTEM) Color.Gray.copy(alpha = 0.2f) else scheme.background)
                    .padding(6.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(3.dp)).background(scheme.primary))
                    repeat(3) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(scheme.primary.copy(alpha = 0.6f)))
                            Spacer(Modifier.width(4.dp))
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(scheme.onSurface.copy(alpha = 0.25f)))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(mode.label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

// ---------------------------------------------------------------- Sincronización

@Composable
fun SyncScreen(nav: NavController, settings: AppSettings) {
    val container = LocalContainer.current
    val update = rememberUpdate()
    val scope = rememberCoroutineScope()
    val user by container.auth.user.collectAsStateWithLifecycle()
    val lastRefresh by container.settings.lastRefresh.collectAsStateWithLifecycle(0L)
    val refreshing by container.feeds.refreshing.collectAsStateWithLifecycle()
    val status by container.cloud.status.collectAsStateWithLifecycle()

    SettingsPage(nav, "Sincronización") {
        SectionTitle("Entre dispositivos")
        SettingsGroup {
            ListItem(
                headlineContent = { Text(if (user != null) "Sincronización en tiempo real activa" else "Sin iniciar sesión") },
                supportingContent = {
                    Text(
                        if (user != null) "Cuenta: ${user?.email}\nLo que marques como visto en un dispositivo se marcará al instante en los demás. Estado: ${status.name.lowercase()}"
                        else if (!container.auth.isAvailable) "Esta compilación no tiene Firebase configurado (ver README)."
                        else "Inicia sesión con Google en la pestaña Perfil para sincronizar canales y episodios vistos."
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        SectionTitle("Buscar episodios nuevos")
        SettingsGroup {
            SYNC_INTERVALS.forEach { (minutes, label) ->
                RadioRow(label, minutes, settings.syncIntervalMin) { update { s -> s.copy(syncIntervalMin = it) } }
            }
        }
        Spacer(Modifier.height(8.dp))
        SettingsGroup {
            SwitchRow("Solo con Wi-Fi", "No gastar datos móviles en segundo plano", settings.wifiOnly) { update { s -> s.copy(wifiOnly = it) } }
            SwitchRow("Actualizar al abrir la app", null, settings.refreshOnOpen) { update { s -> s.copy(refreshOnOpen = it) } }
            ListItem(
                headlineContent = { Text(if (refreshing) "Actualizando…" else "Actualizar ahora") },
                supportingContent = { Text(if (lastRefresh > 0) "Última vez: ${relativeTime(lastRefresh)}" else "Nunca") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(enabled = !refreshing) {
                    scope.launch {
                        container.cloud.pullOnce()
                        container.feeds.refreshAll()
                    }
                },
            )
        }
        Text(
            "Android puede retrasar unos minutos las actualizaciones en segundo plano para ahorrar batería.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

// ---------------------------------------------------------------- Notificaciones

@Composable
fun NotificationsScreen(nav: NavController, settings: AppSettings) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val update = rememberUpdate()
    val scope = rememberCoroutineScope()
    val subs by container.feeds.subscriptionsWithCounts.collectAsStateWithLifecycle(emptyList())
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        update { s -> s.copy(notificationsEnabled = granted) }
    }

    SettingsPage(nav, "Notificaciones") {
        SettingsGroup {
            SwitchRow("Avisar de episodios nuevos", "Con acciones para marcar como visto o ver más tarde", settings.notificationsEnabled) { on ->
                if (on && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else update { s -> s.copy(notificationsEnabled = on) }
            }
            SwitchRow("Avisar de directos (Twitch)", null, settings.notifyLive, enabled = settings.notificationsEnabled) {
                update { s -> s.copy(notifyLive = it) }
            }
            ListItem(
                headlineContent = { Text("Ajustes de notificaciones del sistema") },
                supportingContent = { Text("Sonido, vibración, prioridad…") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                },
            )
        }
        if (subs.isNotEmpty()) {
            SectionTitle("Por canal")
            SettingsGroup {
                subs.forEach { s ->
                    ListItem(
                        headlineContent = { Text(s.subscription.title) },
                        leadingContent = { ChannelAvatar(s.subscription.imageUrl, s.subscription.title, 36.dp) },
                        trailingContent = {
                            Switch(
                                checked = s.subscription.notify,
                                enabled = settings.notificationsEnabled,
                                onCheckedChange = { on -> scope.launch { container.feeds.updateSubscription(s.subscription.copy(notify = on)) } },
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Reproducción

@Composable
fun PlaybackScreen(nav: NavController, settings: AppSettings) {
    val update = rememberUpdate()
    SettingsPage(nav, "Reproducción") {
        SectionTitle("Abrir vídeos de YouTube, Twitch…")
        SettingsGroup {
            OpenMode.entries.forEach { m ->
                RadioRow(
                    m.label + if (m == OpenMode.EXTERNAL) " (YouTube, Twitch…)" else " (sin salir de FeedVibe)",
                    m, settings.openMode,
                ) { update { s -> s.copy(openMode = it) } }
            }
        }
        Text(
            "Los podcasts y archivos de audio/vídeo se reproducen siempre en el reproductor integrado, recordando por dónde ibas en todos tus dispositivos.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        SettingsGroup {
            SwitchRow("Marcar como visto al abrir", "Si no, márcalo tú deslizando o desde el menú", settings.autoMarkOnOpen) {
                update { s -> s.copy(autoMarkOnOpen = it) }
            }
            SwitchRow("Ocultar Shorts y directos de YouTube", "Solo vídeos normales (se aplica en la próxima actualización)", settings.hideShorts) {
                update { s -> s.copy(hideShorts = it) }
            }
        }
        YouTubeKeySection(settings)
    }
}

/** Clave de la API de YouTube: permite listar todos los vídeos de un canal, no solo los 15 últimos. */
@Composable
private fun YouTubeKeySection(settings: AppSettings) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember(settings.youtubeApiKey) { mutableStateOf(settings.youtubeApiKey) }
    var visible by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val builtIn = BuildConfig.YOUTUBE_API_KEY.isNotBlank()

    SectionTitle("YouTube: todos los vídeos del canal")
    SettingsGroup {
        Column(Modifier.padding(16.dp)) {
            Text(
                "YouTube solo publica los 15 últimos vídeos de cada canal en su RSS. Con una clave gratuita de la " +
                    "API de YouTube, FeedVibe puede cargar el canal entero (botón «Cargar todos» en cada canal).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (builtIn && settings.youtubeApiKey.isBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("✔ Esta versión ya incluye una clave. Puedes poner la tuya si quieres.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim(); status = null },
                label = { Text("Clave de la API de YouTube") },
                placeholder = { Text("AIza…") },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Mostrar")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            status?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("✔")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            if (key.isBlank()) {
                                container.settings.update { it.copy(youtubeApiKey = "") }
                                status = "Clave eliminada"
                            } else {
                                status = runCatching { YouTubeApi.validateKey(key) }
                                    .fold({ "✔ Clave válida y guardada" }, { it.message ?: "Clave no válida" })
                                if (status?.startsWith("✔") == true) container.settings.update { it.copy(youtubeApiKey = key) }
                            }
                            checking = false
                        }
                    },
                ) { Text(if (checking) "Comprobando…" else "Guardar") }
                OutlinedButton(onClick = {
                    openUrl(context, "https://console.cloud.google.com/apis/library/youtube.googleapis.com", settings.openMode)
                }) { Text("Conseguir clave") }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Cómo conseguirla (2 minutos, gratis):\n" +
                    "1. Abre «Conseguir clave» y habilita «YouTube Data API v3» (sirve el mismo proyecto de Firebase).\n" +
                    "2. Ve a Credenciales → Crear credenciales → Clave de API.\n" +
                    "3. Copia la clave (empieza por AIza) y pégala aquí.\n" +
                    "Cuota gratuita: 10.000 unidades al día; cargar 1.000 vídeos gasta unas 40.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
