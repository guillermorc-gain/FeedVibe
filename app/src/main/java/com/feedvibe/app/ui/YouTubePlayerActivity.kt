package com.feedvibe.app.ui

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.util.Rational
import android.widget.HorizontalScrollView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.feedvibe.app.MainActivity
import kotlinx.coroutines.flow.first
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.feedvibe.app.FeedVibeApp
import com.feedvibe.app.data.prefs.OpenMode
import com.feedvibe.app.data.sources.RssSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reproductor de YouTube integrado (reproductor oficial incrustado). Va en su propia pantalla
 * clásica, sin Compose: dentro de una pantalla de Compose el vídeo se oía pero se veía en negro.
 * - Guarda por dónde vas (barra de progreso de las listas) y marca el vídeo como visto cuando
 *   faltan 30 segundos o menos.
 * - «Siguiente» pasa al siguiente vídeo sin ver de la cola (la lista de Novedades).
 * - Al volver atrás o salir de la app sigue en una ventana flotante (como YouTube) con
 *   pausa, siguiente y cerrar.
 * - Con la pantalla apagada sigue sonando ([PlaybackService] + notificación de reproducción).
 */
class YouTubePlayerActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_ID = "episodeId"
        private const val EXTRA_QUEUE = "queue"
        private const val ACTION_CONTROL = "com.feedvibe.app.PLAYER_CONTROL"
        private const val EXTRA_CONTROL = "control"
        const val CONTROL_PLAY_PAUSE = 1
        const val CONTROL_NEXT = 2
        const val CONTROL_CLOSE = 3
        const val CONTROL_PLAY = 4
        const val CONTROL_PAUSE = 5
        /** Se marca como visto cuando faltan estos segundos o menos. */
        private const val WATCHED_REMAINING_SEC = 30.0

        /** Orden para el reproductor (desde la ventana flotante, la notificación o el bloqueo). */
        fun controlIntent(context: Context, control: Int) =
            Intent(ACTION_CONTROL).setPackage(context.packageName).putExtra(EXTRA_CONTROL, control)

        /**
         * Hace creer a la página (y al reproductor de YouTube, que va en otro marco) que sigue a la
         * vista: si no, YouTube pausa el vídeo al apagar la pantalla o salir de la app.
         */
        private const val KEEP_VISIBLE_JS = """
(function() {
  try {
    var p = Document.prototype;
    Object.defineProperty(p, 'hidden', { get: function() { return false; }, configurable: true });
    Object.defineProperty(p, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
    Object.defineProperty(p, 'webkitHidden', { get: function() { return false; }, configurable: true });
    Object.defineProperty(p, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
    var block = function(e) { e.stopImmediatePropagation(); };
    document.addEventListener('visibilitychange', block, true);
    document.addEventListener('webkitvisibilitychange', block, true);
    window.addEventListener('pagehide', block, true);
    window.addEventListener('blur', block, true);
  } catch (e) {}
})();
"""

        /** [queue]: episodios que siguen (en orden), para el botón «Siguiente». */
        fun intent(context: Context, episodeId: String, queue: List<String> = emptyList()) =
            Intent(context, YouTubePlayerActivity::class.java)
                .putExtra(EXTRA_ID, episodeId)
                .putStringArrayListExtra(EXTRA_QUEUE, ArrayList(queue.take(500)))
    }

    private val container get() = (application as FeedVibeApp).container
    private var episodeId = ""
    private var queue: List<String> = emptyList()
    private lateinit var webView: WebView
    private lateinit var details: ScrollView
    private lateinit var status: TextView
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    // Lo que informa el reproductor (en segundos).
    private var position = 0.0
    private var duration = 0.0
    private var playing = false
    private var ended = false
    private var title = ""
    private var channelTitle = ""

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(EXTRA_CONTROL, 0)) {
                CONTROL_PLAY_PAUSE -> js("if (player && player.getPlayerState) { if (player.getPlayerState() === 1) player.pauseVideo(); else player.playVideo(); }")
                CONTROL_NEXT -> playNext()
                CONTROL_CLOSE -> finish()
                CONTROL_PLAY -> js("player && player.playVideo && player.playVideo()")
                CONTROL_PAUSE -> js("player && player.pauseVideo && player.pauseVideo()")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.BLACK)

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(PlayerBridge(::onTime, ::onDuration, ::onState, ::onError, ::onPreference), "FeedVibe")
            webChromeClient = chrome
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, KEEP_VISIBLE_JS, setOf("*"))
        }
        status = text(13f, Color.LTGRAY)
        details = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
            addView(details, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        // Deja sitio a las barras del sistema (en Android 15 la app se dibuja debajo de ellas).
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        applyLayout()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> chrome.onHideCustomView()
                    // Como en YouTube: el vídeo sigue en una ventana flotante y vuelves a la app.
                    enterPip() -> showMainScreen()
                    else -> finish()
                }
            }
        })
        ContextCompat.registerReceiver(this, controlReceiver, IntentFilter(ACTION_CONTROL), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Guarda la posición cada cierto tiempo (se sincroniza entre dispositivos).
        lifecycleScope.launch {
            while (isActive) {
                delay(15_000)
                if (playing) savePosition()
            }
        }
        handleIntent(intent)
    }

    // El reproductor es único: abrir otro vídeo (también desde la ventana flotante) lo reutiliza.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        // Sin episodio (p. ej. al tocar la notificación): solo se trae el reproductor al frente.
        val id = intent.getStringExtra(EXTRA_ID) ?: return run { if (episodeId.isEmpty()) finish() }
        queue = intent.getStringArrayListExtra(EXTRA_QUEUE).orEmpty()
        if (id != episodeId) load(id)
    }

    /** Carga un episodio en el reproductor (continuando por donde lo dejaste). */
    private fun load(id: String) {
        if (episodeId.isNotEmpty()) savePosition()
        episodeId = id
        position = 0.0
        duration = 0.0
        playing = false
        ended = false
        lifecycleScope.launch {
            val item = container.feeds.getEpisode(id) ?: return@launch finish()
            if (id != episodeId) return@launch
            val videoId = youtubeVideoId(item)?.takeIf { Regex("[A-Za-z0-9_-]{6,20}").matches(it) }
            if (videoId == null) {
                openUrl(this@YouTubePlayerActivity, item.episode.url, OpenMode.EXTERNAL)
                return@launch finish()
            }
            title = item.episode.title
            channelTitle = item.channelTitle
            notifyPlayback()
            showDetails(item.episode.title, item.channelTitle, relativeTime(item.episode.publishedAt), RssSource.cleanText(item.episode.description), item.episode.url)
            status.text = if (item.watched) "Visto" else "Se marcará como visto cuando falten 30 segundos"
            val start = if (item.positionMs > 0 && !item.watched) item.positionMs / 1000 else 0L
            position = start.toDouble()
            if (item.episode.durationSec > 0) duration = item.episode.durationSec.toDouble()
            // YouTube exige un origen/referente identificable para los reproductores incrustados.
            val origin = "https://$packageName"
            webView.loadDataWithBaseURL(origin, playerHtml(videoId, start, origin, PlayerPrefs.load(this@YouTubePlayerActivity)), "text/html", "utf-8", null)
        }
    }

    /**
     * Pasa al siguiente vídeo sin ver: de la cola con la que se abrió el reproductor o, si no
     * hay, de la lista de Novedades.
     */
    private fun playNext() {
        val current = episodeId
        lifecycleScope.launch {
            val candidates = queue.ifEmpty {
                container.feeds.feedEpisodes.first().filter { youtubeVideoId(it) != null }.map { it.episode.id }
            }
            val after = candidates.indexOf(current).let { if (it >= 0) candidates.drop(it + 1) else candidates }
            val next = after.firstOrNull { id ->
                id != current && container.feeds.getEpisode(id)?.let { !it.watched && youtubeVideoId(it) != null } == true
            }
            if (next == null) {
                Toast.makeText(this@YouTubePlayerActivity, "No hay más vídeos sin ver", Toast.LENGTH_SHORT).show()
            } else if (current == episodeId) {
                load(next)
            }
        }
    }

    private fun js(code: String) {
        if (::webView.isInitialized) webView.evaluateJavascript(code, null)
    }

    // ---------- Ventana flotante (imagen en imagen) ----------

    private fun pipSupported() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(): PictureInPictureParams {
        fun action(control: Int, icon: Int, title: String) = RemoteAction(
            Icon.createWithResource(this, icon), title, title,
            PendingIntent.getBroadcast(
                this, control, controlIntent(this, control),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(
                listOf(
                    if (playing) action(CONTROL_PLAY_PAUSE, android.R.drawable.ic_media_pause, "Pausa")
                    else action(CONTROL_PLAY_PAUSE, android.R.drawable.ic_media_play, "Reproducir"),
                    action(CONTROL_NEXT, android.R.drawable.ic_media_next, "Siguiente"),
                    action(CONTROL_CLOSE, android.R.drawable.ic_menu_close_clear_cancel, "Cerrar"),
                ),
            )
            .build()
    }

    /** Pasa a la ventana flotante. Devuelve false si el móvil no lo permite. */
    private fun enterPip(): Boolean {
        if (!pipSupported() || isInPictureInPictureMode) return false
        if (fullscreenView != null) chrome.onHideCustomView()
        return runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
    }

    /** Vuelve a la pantalla principal de FeedVibe, debajo de la ventana flotante. */
    private fun showMainScreen() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    // Al salir con el botón de inicio, el vídeo sigue en la ventana flotante.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (playing) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Si se cierra la ventana flotante (no se amplía), la actividad ya está parada: se cierra.
        if (!isInPictureInPictureMode) {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                finish()
                return
            }
            // En algunos móviles el aviso llega antes de pararse: se vuelve a comprobar en un momento.
            window.decorView.postDelayed({
                if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) finish()
            }, 600)
        }
        applyLayout()
    }

    private fun updatePipActions() {
        if (pipSupported() && isInPictureInPictureMode) runCatching { setPictureInPictureParams(pipParams()) }
    }

    // ---------- Diseño ----------

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    /**
     * En vertical: vídeo 16:9 arriba y los datos debajo. En horizontal o en la ventana
     * flotante: solo el vídeo, ocupando todo.
     */
    private fun applyLayout() {
        val pip = isInPictureInPictureMode
        val onlyVideo = pip || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val lp = webView.layoutParams as LinearLayout.LayoutParams
        if (onlyVideo) {
            lp.height = 0
            lp.weight = 1f
        } else {
            lp.height = resources.displayMetrics.widthPixels * 9 / 16
            lp.weight = 0f
        }
        webView.layoutParams = lp
        details.visibility = if (onlyVideo) View.GONE else View.VISIBLE
        if (pip) return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (onlyVideo) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun showDetails(title: String, channel: String, date: String, description: String, url: String) {
        val pad = dp(16)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(text(20f, Color.WHITE, bold = true).apply { text = title })
        column.addView(text(14f, Color.LTGRAY).apply { text = listOf(channel, date).filter { it.isNotBlank() }.joinToString(" · ") })
        (status.parent as? ViewGroup)?.removeView(status)
        column.addView(status.apply { setPadding(0, dp(4), 0, 0) })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(4))
        }
        actions.addView(button("Siguiente") { playNext() })
        actions.addView(button("Visto") { markWatched() })
        actions.addView(button("YouTube") { openUrl(this, url, OpenMode.EXTERNAL) })
        actions.addView(button("Compartir") { shareText(this, title, url) })
        column.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        })
        column.addView(text(14f, Color.WHITE).apply {
            text = description
            setPadding(0, dp(8), 0, 0)
        })
        details.removeAllViews()
        details.addView(column)
        details.scrollTo(0, 0)
    }

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextIsSelectable(false)
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor("#33FFFFFF"))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- Avisos del reproductor ----------

    private fun onTime(time: Double, total: Double, state: Int) {
        if (time > 0) position = time
        if (total > 0) duration = total
        setPlaying(state == 1)
        // Faltan 30 segundos o menos (y ya has visto al menos la mitad, por los vídeos muy cortos).
        if (!ended && duration > 0 && duration - position <= WATCHED_REMAINING_SEC && position >= duration / 2) markWatched()
    }

    private fun onDuration(total: Double) {
        if (total > 0) {
            duration = total
            val id = episodeId
            lifecycleScope.launch { container.feeds.setDurationIfUnknown(id, total.toLong()) }
        }
    }

    private fun onState(state: Int) {
        setPlaying(state == 1)
        if (state == 0) markWatched() else if (state == 2) savePosition()
    }

    private fun setPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        updatePipActions()
        notifyPlayback()
    }

    /** Notificación de reproducción (y servicio que mantiene el vídeo con la pantalla apagada). */
    private fun notifyPlayback() {
        if (!isFinishing && title.isNotEmpty()) PlaybackService.update(this, title, channelTitle, playing)
    }

    private fun markWatched() {
        if (ended) return
        ended = true
        val id = episodeId
        container.appScope.launch { container.feeds.setWatched(listOf(id), true) }
        status.text = "Visto"
    }

    /** El reproductor avisa de que has cambiado la velocidad, la calidad o los subtítulos. */
    private fun onPreference(key: String, value: String) = PlayerPrefs.save(this, key, value)

    private fun onError(code: Int) {
        status.text = if (code == 101 || code == 150) "El autor no permite ver este vídeo fuera de YouTube. Usa «YouTube»."
        else "No se ha podido reproducir el vídeo. Usa «YouTube»."
    }

    private fun savePosition() {
        val pos = (position * 1000).toLong()
        val id = episodeId
        if (!ended && pos > 5_000 && id.isNotEmpty()) container.appScope.launch { container.feeds.savePosition(id, pos) }
    }

    override fun onStop() {
        super.onStop()
        // Con la pantalla apagada o la app en segundo plano el vídeo sigue sonando (como YouTube
        // Premium); solo se guarda por dónde ibas.
        savePosition()
    }

    override fun onDestroy() {
        PlaybackService.stop(this)
        runCatching { unregisterReceiver(controlReceiver) }
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("FeedVibe")
            webView.destroy()
        }
        super.onDestroy()
    }

    // Pantalla completa pedida desde el propio reproductor.
    private val chrome = object : WebChromeClient() {
        override fun onShowCustomView(v: View, cb: CustomViewCallback) {
            fullscreenCallback = cb
            fullscreenView = v
            v.setBackgroundColor(Color.BLACK)
            (window.decorView as FrameLayout).addView(
                v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }

        override fun onHideCustomView() {
            fullscreenView?.let { (window.decorView as FrameLayout).removeView(it) }
            fullscreenView = null
            fullscreenCallback?.onCustomViewHidden()
            fullscreenCallback = null
            applyLayout()
        }

        // Sin esto algunos WebView dibujan un cartel gris encima del vídeo.
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }
}

/** Recibe los avisos del reproductor (llegan en otro hilo) y los pasa al hilo principal. */
class PlayerBridge(
    private val time: (Double, Double, Int) -> Unit,
    private val duration: (Double) -> Unit,
    private val state: (Int) -> Unit,
    private val error: (Int) -> Unit,
    private val preference: (String, String) -> Unit,
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

    @JavascriptInterface
    fun onPreference(key: String, value: String) { main.post { this.preference(key, value) } }
}

/**
 * Velocidad, calidad y subtítulos del último vídeo, para usarlos en el siguiente
 * (se guardan en este dispositivo).
 */
data class PlayerPrefs(val rate: Double, val quality: String, val captions: String) {
    companion object {
        const val RATE = "rate"
        const val QUALITY = "quality"
        const val CAPTIONS = "captions"
        private val SAFE = Regex("[A-Za-z0-9_-]{0,20}")

        private fun prefs(context: Context) = context.getSharedPreferences("youtube_player", Context.MODE_PRIVATE)

        fun load(context: Context): PlayerPrefs {
            val p = prefs(context)
            return PlayerPrefs(
                rate = p.getFloat(RATE, 1f).toDouble().takeIf { it in 0.25..2.0 } ?: 1.0,
                // Solo valores seguros: se insertan en la página del reproductor.
                quality = p.getString(QUALITY, "").orEmpty().takeIf { SAFE.matches(it) }.orEmpty(),
                captions = p.getString(CAPTIONS, "").orEmpty().takeIf { SAFE.matches(it) }.orEmpty(),
            )
        }

        fun save(context: Context, key: String, value: String) {
            val p = prefs(context).edit()
            when (key) {
                RATE -> value.toFloatOrNull()?.takeIf { it in 0.25f..2f }?.let { p.putFloat(RATE, it) }
                QUALITY -> if (SAFE.matches(value) && value != "unknown") p.putString(QUALITY, value)
                CAPTIONS -> if (SAFE.matches(value)) p.putString(CAPTIONS, value)
            }
            p.apply()
        }
    }
}

private fun playerHtml(videoId: String, start: Long, origin: String, prefs: PlayerPrefs): String {
    val cc = prefs.captions
    // Subtítulos: si en el vídeo anterior estaban puestos, se cargan en el mismo idioma.
    val ccVars = if (cc.isNotEmpty()) ", cc_load_policy: 1, cc_lang_pref: '$cc'" else ""
    // La calidad no se puede imponer con la API oficial; «vq» es una pista que YouTube puede ignorar.
    val qVars = if (prefs.quality.isNotEmpty() && prefs.quality != "auto") ", vq: '${prefs.quality}'" else ""
    return """
<!doctype html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}#p{position:fixed;top:0;left:0;width:100vw;height:100vh;border:0}</style>
</head><body>
<div id="p"></div>
<script>
var player;
var RATE = ${prefs.rate}, QUALITY = '${prefs.quality}', CC = '$cc';
var readyAt = 0, lastCc = null, pendingCc = null;
var tag = document.createElement('script');
tag.src = 'https://www.youtube.com/iframe_api';
document.head.appendChild(tag);
function applyPrefs(p) {
  if (RATE !== 1 && p.getPlaybackRate() !== RATE) p.setPlaybackRate(RATE);
  if (QUALITY && QUALITY !== 'auto' && p.setPlaybackQuality) p.setPlaybackQuality(QUALITY);
  if (CC) {
    try { p.loadModule('captions'); p.setOption('captions', 'track', { languageCode: CC }); } catch (err) {}
  }
}
function currentCc() {
  try {
    var t = player.getOption('captions', 'track');
    return (t && t.languageCode) ? t.languageCode : '';
  } catch (err) { return ''; }
}
function onYouTubeIframeAPIReady() {
  player = new YT.Player('p', {
    width: '100%', height: '100%', videoId: '$videoId',
    playerVars: { autoplay: 1, playsinline: 1, rel: 0, start: $start, origin: '$origin', widget_referrer: '$origin'$ccVars$qVars },
    events: {
      onReady: function(e) {
        readyAt = Date.now();
        FeedVibe.onDuration(e.target.getDuration());
        applyPrefs(e.target);
        e.target.playVideo();
      },
      onStateChange: function(e) {
        // Al empezar a reproducirse se vuelve a aplicar (algunos ajustes no se aceptan antes).
        if (e.data === 1 && Date.now() - readyAt < 10000) applyPrefs(e.target);
        FeedVibe.onState(e.data);
      },
      onPlaybackRateChange: function(e) {
        if (Date.now() - readyAt > 3000) { RATE = e.data; FeedVibe.onPreference('rate', String(e.data)); }
      },
      onPlaybackQualityChange: function(e) {
        if (Date.now() - readyAt > 3000) FeedVibe.onPreference('quality', String(e.data));
      },
      onError: function(e) { FeedVibe.onError(e.data); }
    }
  });
  setInterval(function() {
    if (!player || !player.getCurrentTime) return;
    FeedVibe.onTime(player.getCurrentTime(), player.getDuration(), player.getPlayerState());
    // Subtítulos: no hay evento, se comprueba cada segundo. Pasados unos segundos desde el
    // arranque (para no confundir la carga inicial) y solo si el cambio se mantiene.
    if (Date.now() - readyAt < 8000 || player.getPlayerState() !== 1) return;
    var c = currentCc();
    // Lo primero que se ve es lo que aplicó el reproductor (puede que el vídeo no tenga ese idioma):
    // no se guarda; solo se guardan los cambios que hagas después.
    if (lastCc === null) { lastCc = c; return; }
    if (c === lastCc) { pendingCc = null; return; }
    if (pendingCc === c) { lastCc = c; CC = c; pendingCc = null; FeedVibe.onPreference('captions', c); }
    else pendingCc = c;
  }, 1000);
}
</script>
</body></html>
""".trimIndent()
}
