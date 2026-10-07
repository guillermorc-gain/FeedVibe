package com.feedvibe.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.feedvibe.app.data.prefs.AppSettings
import com.feedvibe.app.notify.Notifier
import com.feedvibe.app.ui.AppRoot
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.theme.FeedVibeTheme
import kotlinx.coroutines.launch

/** Lo que llega de fuera: URL compartida o episodio tocado en una notificación. */
data class ExternalRequest(val sharedUrl: String? = null, val episodeId: String? = null, val nonce: Long = System.nanoTime())

class MainActivity : ComponentActivity() {
    private val external = mutableStateOf<ExternalRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FeedVibeApp).container
        handleIntent(intent)
        com.feedvibe.app.update.UpdatedReceiver.clear(this)

        // Solo al abrir la app de verdad, no cuando Android vuelve a crear la pantalla.
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                val s = container.settings.current()
                if (s.refreshOnOpen) runCatching { container.feeds.refreshAll() }
            }
            lifecycleScope.launch {
                // Buscar actualizaciones cada vez que se abre la app.
                if (container.settings.current().autoUpdateCheck) {
                    container.settings.setLastUpdateCheck(System.currentTimeMillis())
                    container.updater.check()
                }
            }
        }
        container.startBackgroundJobs()

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val request by external
            CompositionLocalProvider(LocalContainer provides container) {
                FeedVibeTheme(settings ?: AppSettings()) {
                    if (settings != null) {
                        AppRoot(
                            settings = settings!!,
                            external = request,
                            onExternalHandled = { external.value = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when {
            intent.action == Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                external.value = ExternalRequest(sharedUrl = it)
            }
            intent.action == Intent.ACTION_VIEW && intent.data != null ->
                external.value = ExternalRequest(sharedUrl = intent.data.toString())
            intent.hasExtra(Notifier.EXTRA_EPISODE_ID) ->
                external.value = ExternalRequest(episodeId = intent.getStringExtra(Notifier.EXTRA_EPISODE_ID))
        }
    }
}
