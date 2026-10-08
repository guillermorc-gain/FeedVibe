package com.feedvibe.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feedvibe.app.data.access.ADMIN_EMAIL
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.relativeTime
import kotlinx.coroutines.launch

/**
 * Solo para el administrador: quién usa la app (nombre, correo, versión instalada), a quién se
 * autoriza y si la app está publicada para todo el mundo.
 */
@Composable
fun AdminSections(snackbar: SnackbarHostState) {
    val access = LocalContainer.current.access
    val scope = rememberCoroutineScope()
    val members by access.members.collectAsStateWithLifecycle()
    val config by access.config.collectAsStateWithLifecycle()
    val allowed = config?.allowed.orEmpty()
    val public = config?.public == true
    var addDialog by remember { mutableStateOf(false) }

    fun run(ok: String, block: suspend () -> Unit) = scope.launch {
        runCatching { block() }
            .onSuccess { snackbar.showSnackbar(ok) }
            .onFailure { snackbar.showSnackbar("No se pudo guardar: ${it.message}. Revisa las reglas de Firestore.") }
    }

    SectionTitle("Usuarios autorizados")
    SettingsGroup {
        Text(
            if (public) "La app está publicada: cualquiera puede usarla. Aquí ves quién la usa."
            else "Solo pueden usar la app las cuentas que autorices. Quien inicie sesión sin permiso aparece aquí como pendiente.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        val known = members.map { it.email }.toSet()
        members.forEach { m ->
            val admin = m.email.equals(ADMIN_EMAIL, ignoreCase = true)
            val isAllowed = admin || m.email in allowed
            ListItem(
                headlineContent = { Text(m.name.ifBlank { m.email }, fontWeight = FontWeight.SemiBold) },
                supportingContent = {
                    Column {
                        Text(m.email)
                        Text(
                            buildString {
                                append(if (m.version.isNotBlank()) "Versión ${m.version}" else "Versión desconocida")
                                if (m.device.isNotBlank()) append(" · ${m.device}")
                                if (m.lastSeen > 0) append(" · ${relativeTime(m.lastSeen)}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            when {
                                admin -> "Administrador"
                                isAllowed -> "Autorizado"
                                public -> "Sin autorizar (entra porque la app es pública)"
                                else -> "Pendiente de autorización"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isAllowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                },
                leadingContent = { Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = {
                    if (!admin) Switch(
                        checked = isAllowed,
                        onCheckedChange = { on ->
                            run(if (on) "${m.email} autorizado" else "${m.email} ya no está autorizado") { access.setAllowed(m.email, on) }
                        },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        // Correos autorizados que aún no han abierto la app.
        allowed.filter { it !in known }.sorted().forEach { email ->
            ListItem(
                headlineContent = { Text(email) },
                supportingContent = { Text("Autorizado · aún no ha iniciado sesión", style = MaterialTheme.typography.labelSmall) },
                leadingContent = { Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingContent = {
                    Switch(checked = true, onCheckedChange = { run("$email ya no está autorizado") { access.setAllowed(email, false) } })
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        ListItem(
            headlineContent = { Text("Autorizar una cuenta de Google") },
            supportingContent = { Text("Escribe su correo; podrá entrar al iniciar sesión") },
            leadingContent = { Icon(Icons.Filled.PersonAdd, null, tint = MaterialTheme.colorScheme.primary) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { addDialog = true },
        )
    }

    SectionTitle("Publicación")
    SettingsGroup {
        ListItem(
            headlineContent = { Text("Publicar para todos") },
            supportingContent = {
                Text(
                    if (public) "Publicada: cualquier persona con la app puede usarla, sin autorización."
                    else "Sin publicar: solo tú y las cuentas autorizadas."
                )
            },
            leadingContent = { Icon(Icons.Filled.Public, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = {
                Switch(
                    checked = public,
                    onCheckedChange = { on ->
                        run(if (on) "FeedVibe publicada para todos" else "FeedVibe ya no es pública") { access.setPublic(on) }
                    },
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }

    if (addDialog) {
        var email by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("Autorizar cuenta") },
            text = {
                OutlinedTextField(value = email, onValueChange = { email = it }, singleLine = true, label = { Text("Correo de Google") })
            },
            confirmButton = {
                TextButton(
                    enabled = email.contains('@'),
                    onClick = {
                        addDialog = false
                        val e = email.trim()
                        run("$e autorizado") { access.setAllowed(e, true) }
                    },
                ) { Text("Autorizar") }
            },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text("Cancelar") } },
        )
    }
}
