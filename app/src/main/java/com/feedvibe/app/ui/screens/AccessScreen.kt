package com.feedvibe.app.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feedvibe.app.data.access.Access
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.components.BrandTitle
import kotlinx.coroutines.launch

/** Se muestra en lugar de la app mientras la cuenta no esté autorizada (y la app no sea pública). */
@Composable
fun AccessScreen(access: Access) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user by container.auth.user.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandTitle(fontSize = MaterialTheme.typography.headlineLarge.fontSize)
            Spacer(Modifier.height(32.dp))
            when (access) {
                Access.CHECKING -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Comprobando el acceso…", textAlign = TextAlign.Center)
                }
                Access.NEEDS_SIGN_IN -> {
                    Text(
                        "FeedVibe aún no está publicada. Inicia sesión con tu cuenta de Google para pedir acceso.",
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        enabled = !busy,
                        onClick = {
                            val activity = context as? Activity ?: return@Button
                            busy = true
                            scope.launch {
                                container.auth.signIn(activity).onFailure { error = it.message }
                                busy = false
                            }
                        },
                    ) { Text("Iniciar sesión con Google") }
                }
                Access.PENDING -> {
                    Text(
                        "Tu cuenta ${user?.email.orEmpty()} está pendiente de autorización.\n\nEn cuanto el administrador te autorice, la app se abrirá sola.",
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            container.cloud.signedOut()
                            container.auth.signOut()
                        }
                    }) { Text("Usar otra cuenta") }
                }
                Access.ALLOWED -> Unit
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }
}
