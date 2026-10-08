package com.feedvibe.app.ui.screens

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.feedvibe.app.data.prefs.AppSettings
import com.feedvibe.app.data.sync.SyncStatus
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.Routes
import com.feedvibe.app.data.access.isAdmin
import com.feedvibe.app.ui.components.UserAvatar
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(nav: NavController, settings: AppSettings) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val user by container.auth.user.collectAsStateWithLifecycle()
    val localPhoto by container.profile.photo.collectAsStateWithLifecycle(null)
    val syncStatus by container.cloud.status.collectAsStateWithLifecycle()
    val syncDetail by container.cloud.detail.collectAsStateWithLifecycle()

    var photoSheet by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }

    val photo = localPhoto ?: user?.photoUrl
    val displayName = settings.nickname.ifBlank { user?.name?.ifBlank { null } ?: "Invitado" }

    fun setPhoto(uri: Uri?) {
        uri ?: return
        scope.launch {
            runCatching { container.profile.setPhoto(uri) }
                .onSuccess { snackbar.showSnackbar("Foto de perfil actualizada") }
                .onFailure { snackbar.showSnackbar(it.message ?: "No se pudo cambiar la foto") }
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { setPhoto(it) }
    val cameraUri = remember {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(dir, "profile_capture.jpg"))
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) setPhoto(cameraUri) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // ---------- Cabecera (discreta): foto y nombre ----------
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.clickable { photoSheet = true }) {
                    UserAvatar(photo, 64.dp, Modifier.border(3.dp, Color.White, CircleShape))
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.PhotoCamera, "Cambiar foto", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).clickable { editName = true }) {
                    Text(displayName, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Edit, "Editar nombre", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                }
            }

            // ---------- Ajustes (de este dispositivo; no se sincronizan) ----------
            SectionTitle("Ajustes")
            SettingsGroup {
                SettingsLink(Icons.Filled.Palette, "Apariencia", "Tema, colores y estilo de lista") { nav.navigate(Routes.APPEARANCE) }
                SettingsLink(Icons.Filled.Sync, "Sincronización", "Periodo de actualización y dispositivos") { nav.navigate(Routes.SYNC) }
                SettingsLink(Icons.Filled.Notifications, "Notificaciones", "Avisos de episodios nuevos y directos") { nav.navigate(Routes.NOTIFICATIONS) }
                SettingsLink(Icons.Filled.PlayCircle, "Reproducción", "Cómo se abren los vídeos, Shorts…") { nav.navigate(Routes.PLAYBACK) }
                SettingsLink(Icons.Filled.Backup, "Copias de seguridad", "Archivo en el teléfono y OPML") { nav.navigate(Routes.BACKUP) }
                SettingsLink(Icons.Filled.SystemUpdate, "Actualizaciones", "Versión ${container.updater.currentVersion}") { nav.navigate(Routes.ABOUT) }
            }

            // ---------- Administración (solo la cuenta del administrador) ----------
            if (isAdmin(user)) AdminSections(snackbar)

            // ---------- Perfil: cuenta de Google y cerrar sesión ----------
            SectionTitle("Perfil")
            SettingsGroup {
                val u = user
                if (u != null) {
                    ListItem(
                        headlineContent = { Text(u.name.ifBlank { u.email }) },
                        supportingContent = {
                            Column {
                                Text(u.email)
                                Text(
                                    when (syncStatus) {
                                        SyncStatus.SYNCED -> "● Sincronizado"
                                        SyncStatus.CONNECTING -> "● Conectando…"
                                        SyncStatus.ERROR -> "● Error de sincronización"
                                        SyncStatus.OFF -> "● Sin sincronizar"
                                    },
                                    color = when (syncStatus) {
                                        SyncStatus.SYNCED -> MaterialTheme.colorScheme.primary
                                        SyncStatus.ERROR -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                syncDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        },
                        leadingContent = { UserAvatar(u.photoUrl, 40.dp) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    SettingsLink(Icons.AutoMirrored.Filled.Logout, "Cerrar sesión", null, tint = MaterialTheme.colorScheme.error) { confirmLogout = true }
                } else {
                    ListItem(
                        headlineContent = { Text("Cuenta de Google") },
                        supportingContent = { Text("Inicia sesión para sincronizar lo que ves entre tus dispositivos") },
                        leadingContent = { Icon(Icons.Filled.AccountCircle, null, tint = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    Button(
                        onClick = {
                            val activity = context as? Activity ?: return@Button
                            signingIn = true
                            scope.launch {
                                container.auth.signIn(activity)
                                    .onSuccess { snackbar.showSnackbar("¡Hola, ${it.name}! Tus datos se sincronizarán.") }
                                    .onFailure { snackbar.showSnackbar(it.message ?: "No se pudo iniciar sesión") }
                                signingIn = false
                            }
                        },
                        enabled = !signingIn,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    ) { Text("Iniciar sesión con Google") }
                }
            }

            Text(
                "Aplicación desarrollada por Guillermo Ríos Correa",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 24.dp),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (photoSheet) {
        ModalBottomSheet(onDismissRequest = { photoSheet = false }) {
            Column(Modifier.padding(bottom = 24.dp).navigationBarsPadding()) {
                Text("Foto de perfil", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                SheetOption(Icons.Filled.PhotoCamera, "Hacer una foto") { photoSheet = false; camera.launch(cameraUri) }
                SheetOption(Icons.Filled.PhotoLibrary, "Elegir de la galería") {
                    photoSheet = false
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                if (localPhoto != null) {
                    SheetOption(Icons.Filled.Delete, if (user?.photoUrl != null) "Usar la foto de Google" else "Eliminar foto") {
                        photoSheet = false
                        scope.launch { container.profile.removePhoto() }
                    }
                }
            }
        }
    }

    if (editName) {
        var text by remember { mutableStateOf(displayName.takeIf { it != "Invitado" }.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editName = false },
            title = { Text("¿Cómo quieres que te llamemos?") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Nombre") }) },
            confirmButton = {
                TextButton(onClick = { editName = false; scope.launch { container.profile.setNickname(text) } }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { editName = false }) { Text("Cancelar") } },
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Cerrar sesión") },
            text = { Text("Tus canales y lo que has visto se quedan en este dispositivo, pero dejarán de sincronizarse.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    scope.launch {
                        container.cloud.signedOut()
                        container.auth.signOut()
                    }
                }) { Text("Cerrar sesión") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SheetOption(icon: ImageVector, text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column { content() }
    }
}

@Composable
fun SettingsLink(icon: ImageVector, title: String, subtitle: String?, tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, color = if (tint == MaterialTheme.colorScheme.error) tint else Color.Unspecified) },
        supportingContent = if (subtitle != null) { { Text(subtitle) } } else null,
        leadingContent = {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = tint) }
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

