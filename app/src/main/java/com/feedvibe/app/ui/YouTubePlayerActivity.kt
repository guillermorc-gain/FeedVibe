package com.feedvibe.app.ui

import android.annotation.SuppressLint
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
 * Guarda por dónde vas (para la barra de progreso de las listas) y marca el vídeo como visto
 * solo cuando llega al final.
 */
class YouTubePlayerActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_ID = "episodeId"

        fun intent(context: Context, episodeId: String) =
            Intent(context, YouTubePlayerActivity::class.java).putExtra(EXTRA_ID, episodeId)
    }

    private val container get() = (application as FeedVibeApp).container
    private lateinit var episodeId: String
    private lateinit var webView: WebView
    private lateinit var details: ScrollView
    private lateinit var status: TextView
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    // Lo que informa el reproductor (en segundos).
    private var position = 0.0
    private var playing = false
    private var ended = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        episodeId = intent.getStringExtra(EXTRA_ID) ?: return finish()
        window.decorView.setBackgroundColor(Color.BLACK)

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(PlayerBridge(::onTime, ::onDuration, ::onState, ::onError), "FeedVibe")
            webChromeClient = chrome
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
        applyOrientation(resources.configuration.orientation)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenView != null) chrome.onHideCustomView() else finish()
            }
        })

        lifecycleScope.launch {
            val item = container.feeds.getEpisode(episodeId) ?: return@launch finish()
            val videoId = youtubeVideoId(item)?.takeIf { Regex("[A-Za-z0-9_-]{6,20}").matches(it) }
            if (videoId == null) {
                openUrl(this@YouTubePlayerActivity, item.episode.url, OpenMode.EXTERNAL)
                return@launch finish()
            }
            showDetails(item.episode.title, item.channelTitle, relativeTime(item.episode.publishedAt), RssSource.cleanText(item.episode.description), item.episode.url)
            status.text = if (item.watched) "Visto" else "Se marcará como visto cuando lo veas entero"
            val start = if (item.positionMs > 0 && !item.watched) item.positionMs / 1000 else 0L
            position = start.toDouble()
            // YouTube exige un origen/referente identificable para los reproductores incrustados.
            val origin = "https://$packageName"
            webView.loadDataWithBaseURL(origin, playerHtml(videoId, start, origin), "text/html", "utf-8", null)
        }

        // Guarda la posición cada cierto tiempo (se sincroniza entre dispositivos).
        lifecycleScope.launch {
            while (isActive) {
                delay(15_000)
                if (playing) savePosition()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig.orientation)
    }

    /** En vertical: vídeo 16:9 arriba y los datos debajo. En horizontal: vídeo a pantalla completa. */
    private fun applyOrientation(orientation: Int) {
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val lp = webView.layoutParams as LinearLayout.LayoutParams
        if (landscape) {
            lp.height = 0
            lp.weight = 1f
        } else {
            lp.height = resources.displayMetrics.widthPixels * 9 / 16
            lp.weight = 0f
        }
        webView.layoutParams = lp
        details.visibility = if (landscape) View.GONE else View.VISIBLE
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (landscape) controller.hide(WindowInsetsCompat.Type.systemBars())
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
        column.addView(status.apply { setPadding(0, dp(4), 0, 0) })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(4))
        }
        actions.addView(button("Abrir en YouTube") { openUrl(this, url, OpenMode.EXTERNAL) })
        actions.addView(button("Marcar como visto") {
            ended = true
            lifecycleScope.launch { container.feeds.setWatched(listOf(episodeId), true) }
            status.text = "Visto"
        })
        actions.addView(button("Compartir") { shareText(this, title, url) })
        column.addView(actions)
        column.addView(text(14f, Color.WHITE).apply {
            text = description
            setPadding(0, dp(8), 0, 0)
        })
        details.removeAllViews()
        details.addView(column)
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
        setPadding(dp(12), dp(8), dp(12), dp(8))
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
        playing = state == 1
    }

    private fun onDuration(total: Double) {
        if (total > 0) lifecycleScope.launch { container.feeds.setDurationIfUnknown(episodeId, total.toLong()) }
    }

    private fun onState(state: Int) {
        playing = state == 1
        // 0 = terminado: solo entonces se marca como visto.
        if (state == 0 && !ended) {
            ended = true
            container.appScope.launch { container.feeds.setWatched(listOf(episodeId), true) }
            status.text = "Visto"
        } else if (state == 2) {
            savePosition()
        }
    }

    private fun onError(code: Int) {
        status.text = if (code == 101 || code == 150) "El autor no permite ver este vídeo fuera de YouTube. Usa «Abrir en YouTube»."
        else "No se ha podido reproducir el vídeo. Usa «Abrir en YouTube»."
    }

    private fun savePosition() {
        val pos = (position * 1000).toLong()
        if (!ended && pos > 5_000) container.appScope.launch { container.feeds.savePosition(episodeId, pos) }
    }

    override fun onStop() {
        super.onStop()
        if (::webView.isInitialized) webView.evaluateJavascript("player && player.pauseVideo && player.pauseVideo()", null)
        savePosition()
    }

    override fun onDestroy() {
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
            applyOrientation(resources.configuration.orientation)
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
<style>html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}#p{position:fixed;top:0;left:0;width:100vw;height:100vh;border:0}</style>
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
