package com.feedvibe.app.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.feedvibe.app.data.prefs.OpenMode
import com.feedvibe.app.data.sources.RssSource
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.components.ScreenScaffold
import com.feedvibe.app.ui.openUrl
import com.feedvibe.app.ui.relativeTime
import com.feedvibe.app.ui.shareText
import com.feedvibe.app.ui.youtubeVideoId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reproductor de YouTube integrado (reproductor oficial incrustado). Guarda por dónde vas,
 * para que se vea la barra de progreso en las listas, y marca el vídeo como visto solo
 * cuando llega al final.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayerScreen(nav: NavController, episodeId: String) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val item by remember(episodeId) { container.feeds.episode(episodeId) }.collectAsStateWithLifecycle(null)
    val current = item
    // Solo identificadores válidos de YouTube: se insertan en la página del reproductor.
    val videoId = current?.let { youtubeVideoId(it) }?.takeIf { Regex("[A-Za-z0-9_-]{6,20}").matches(it) }

    // Posición y duración que informa el reproductor (en segundos).
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var fullscreen by remember { mutableStateOf<View?>(null) }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    fun savePosition() {
        val pos = (position * 1000).toLong()
        if (!ended && pos > 5_000) container.appScope.launch { container.feeds.savePosition(episodeId, pos) }
    }

    val bridge = remember {
        PlayerBridge(
            time = { time, total, state ->
                if (time > 0) position = time.toFloat()
                if (total > 0) duration = total.toFloat()
                playing = state == 1
            },
            duration = { total ->
                if (total > 0) {
                    duration = total.toFloat()
                    container.appScope.launch { container.feeds.setDurationIfUnknown(episodeId, total.toLong()) }
                }
            },
            state = { state ->
                playing = state == 1
                // 0 = terminado: solo entonces se marca como visto.
                if (state == 0 && !ended) {
                    ended = true
                    container.appScope.launch { container.feeds.setWatched(listOf(episodeId), true) }
                } else if (state == 2) {
                    savePosition()
                }
            },
            error = { error = it },
        )
    }

    // Pantalla completa del reproductor: se muestra encima de todo y en horizontal.
    val activity = context as? Activity
    val chrome = remember {
        object : WebChromeClient() {
            private var callback: CustomViewCallback? = null
            private var oldOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            override fun onShowCustomView(v: View, cb: CustomViewCallback) {
                val act = activity ?: return cb.onCustomViewHidden()
                callback = cb
                oldOrientation = act.requestedOrientation
                (act.window.decorView as FrameLayout).addView(
                    v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                v.setBackgroundColor(android.graphics.Color.BLACK)
                WindowCompat.getInsetsController(act.window, v).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                fullscreen = v
            }

            // Sin esto algunos WebView dibujan un cartel gris/negro encima del vídeo.
            override fun getDefaultVideoPoster(): Bitmap =
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

            override fun onHideCustomView() {
                val act = activity ?: return
                fullscreen?.let { (act.window.decorView as FrameLayout).removeView(it) }
                WindowCompat.getInsetsController(act.window, act.window.decorView).show(WindowInsetsCompat.Type.systemBars())
                act.requestedOrientation = oldOrientation
                fullscreen = null
                callback?.onCustomViewHidden()
                callback = null
            }
        }
    }
    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(bridge, "FeedVibe")
            webChromeClient = chrome
        }
    }

    BackHandler(enabled = fullscreen != null) { chrome.onHideCustomView() }

    // Carga el vídeo una sola vez, continuando por donde lo dejaste.
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(videoId) {
        val it = current ?: return@LaunchedEffect
        if (loaded || videoId == null) return@LaunchedEffect
        // El vídeo debe empezar con el reproductor ya colocado en pantalla; si arranca antes
        // (tamaño 0) algunos móviles reproducen el sonido pero dejan la imagen en negro.
        while (!webView.isAttachedToWindow || webView.width == 0 || webView.height == 0) delay(16)
        val start = if (it.positionMs > 0 && !it.watched) it.positionMs / 1000 else 0L
        position = start.toFloat()
        if (it.episode.durationSec > 0) duration = it.episode.durationSec.toFloat()
        // YouTube exige un origen/referente identificable para los reproductores incrustados.
        val origin = "https://${context.packageName}"
        webView.loadDataWithBaseURL(origin, playerHtml(videoId, start, origin), "text/html", "utf-8", null)
        loaded = true
    }

    // Guarda la posición cada cierto tiempo (se sincroniza entre dispositivos).
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            if (playing) savePosition()
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                webView.evaluateJavascript("player && player.pauseVideo && player.pauseVideo()", null)
                savePosition()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (fullscreen != null) chrome.onHideCustomView()
            savePosition()
            webView.removeJavascriptInterface("FeedVibe")
            webView.destroy()
        }
    }

    ScreenScaffold(
        title = current?.channelTitle ?: "",
        onBack = { nav.popBackStack() },
        actions = {
            if (current != null) {
                IconButton(onClick = { openUrl(context, current.episode.url, OpenMode.EXTERNAL) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, "Abrir en YouTube")
                }
                IconButton(onClick = { shareText(context, current.episode.title, current.episode.url) }) { Icon(Icons.Filled.Share, "Compartir") }
                IconButton(onClick = { container.appScope.launch { container.feeds.toggleWatched(current) } }) {
                    Icon(Icons.Filled.CheckCircle, if (current.watched) "Marcar como no visto" else "Marcar como visto",
                        tint = if (current.watched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    ) { padding ->
        // El reproductor queda fijo arriba (fuera de la zona que se desplaza) y solo se desplaza el texto.
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                if (current != null && (error != null || videoId == null)) {
                    Column(
                        Modifier.fillMaxSize().background(Color.Black).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Text(
                            if (error == 101 || error == 150) "El autor no permite ver este vídeo fuera de YouTube."
                            else "No se ha podido reproducir el vídeo.",
                            color = Color.White,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { openUrl(context, current.episode.url, OpenMode.EXTERNAL) }) { Text("Abrir en YouTube") }
                    }
                }
            }
            if (duration > 0) {
                LinearProgressIndicator(
                    progress = { if (ended) 1f else (position / duration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
            if (current != null) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text(current.episode.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${current.channelTitle} · ${relativeTime(current.episode.publishedAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (current.watched) "Visto" else "Se marcará como visto cuando lo veas entero",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (current.watched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(RssSource.cleanText(current.episode.description), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Recibe los avisos del reproductor (llegan en otro hilo) y los pasa al hilo principal. */
class PlayerBridge(
    private val time: (Double, Double, Int) -> Unit,
    private val duration: (Double) -> Unit,
    private val state: (Int) -> Unit,
    private val error: (Int) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onTime(time: Double, total: Double, state: Int) { main.post { this.time(time, total, state) } }

    @JavascriptInterface
    fun onDuration(total: Double) { main.post { this.duration(total) } }

    @JavascriptInterface
    fun onState(state: Int) { main.post { this.state(state) } }

    @JavascriptInterface
    fun onError(code: Int) { main.post { this.error(code) } }
}

private fun playerHtml(videoId: String, start: Long, origin: String) = """
<!doctype html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;padding:0;height:100%;background:#000;overflow:hidden}#p{position:absolute;top:0;left:0;width:100%;height:100%}</style>
</head><body>
<div id="p"></div>
<script>
var player;
var tag = document.createElement('script');
tag.src = 'https://www.youtube.com/iframe_api';
document.head.appendChild(tag);
function onYouTubeIframeAPIReady() {
  player = new YT.Player('p', {
    width: '100%', height: '100%', videoId: '$videoId',
    playerVars: { autoplay: 1, playsinline: 1, rel: 0, start: $start, origin: '$origin', widget_referrer: '$origin' },
    events: {
      onReady: function(e) { FeedVibe.onDuration(e.target.getDuration()); e.target.playVideo(); },
      onStateChange: function(e) { FeedVibe.onState(e.data); },
      onError: function(e) { FeedVibe.onError(e.data); }
    }
  });
  setInterval(function() {
    if (player && player.getCurrentTime) FeedVibe.onTime(player.getCurrentTime(), player.getDuration(), player.getPlayerState());
  }, 1000);
}
</script>
</body></html>
""".trimIndent()
