package com.feedvibe.app.radio

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.feedvibe.app.FeedVibeApp
import com.feedvibe.app.R
import com.feedvibe.app.data.radio.Episode
import com.feedvibe.app.data.radio.Stream
import com.feedvibe.app.data.radio.TuneIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Formato de las grabaciones. */
enum class RecordFormat(val label: String, val detail: String) {
    ORIGINAL("Original (MP3/AAC)", "Tal cual lo emite la radio: misma calidad, lo que menos ocupa"),
    FLAC("FLAC", "Sin pérdidas: misma calidad que la emisión, ocupa unas 6 veces más"),
    WAV("WAV", "Sin comprimir: lo que más ocupa");

    /** Lo que ocupará aproximadamente una grabación de [seconds] de una emisión de [kbps]. */
    fun estimateBytes(kbps: Int, seconds: Long): Long = when (this) {
        ORIGINAL -> kbps.coerceAtLeast(64) * 1000L / 8 * seconds
        // Audio de radio descomprimido a 44,1 kHz estéreo (1411 kbps) y comprimido sin pérdidas (~60 %).
        FLAC -> (176_400L * seconds * 0.6).toLong()
        WAV -> 176_400L * seconds
    }

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: FLAC
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale("es"), "%.1f GB", bytes / 1e9)
    else -> "${(bytes / 1_000_000).coerceAtLeast(1)} MB"
}

/** Una grabación (ahora o programada). [endAtMs] = 0: hasta pararla. */
@Serializable
data class RecordJob(
    val id: Long,
    val stationId: String,
    val stationName: String,
    val image: String? = null,
    val program: String = "",
    val startAtMs: Long = 0,
    val endAtMs: Long = 0,
)

/** Lo que se está grabando ahora. */
data class RecordingState(val job: RecordJob, val startedMs: Long, val bytes: Long, val status: String)

/**
 * Graba la radio en primer plano (aunque la pantalla esté apagada o la app cerrada): descarga la
 * emisión tal cual y al terminar la guarda en el formato elegido con la emisora, el programa y el logo.
 */
class RecordService : Service() {
    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 6001
        private const val EXTRA_JOB = "job"
        private const val ACTION_STOP = "com.feedvibe.app.RECORD_STOP"

        private val _state = MutableStateFlow<RecordingState?>(null)
        val state: StateFlow<RecordingState?> = _state.asStateFlow()

        fun start(context: Context, job: RecordJob) {
            val i = Intent(context, RecordService::class.java)
                .putExtra(EXTRA_JOB, com.feedvibe.app.data.sources.AppJson.encodeToString(RecordJob.serializer(), job))
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    @Volatile private var stopRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Preparando la grabación…", null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            if (work == null) stopSelf()
            return START_NOT_STICKY
        }
        val job = intent?.getStringExtra(EXTRA_JOB)?.let {
            runCatching { com.feedvibe.app.data.sources.AppJson.decodeFromString(RecordJob.serializer(), it) }.getOrNull()
        }
        if (job == null || work != null) {
            if (work == null) stopSelf()
            return START_NOT_STICKY
        }
        stopRequested = false
        work = scope.launch {
            runCatching { record(job) }.onFailure { e ->
                notifyDone("No se pudo grabar ${job.stationName}", e.message ?: "Error")
            }
            _state.value = null
            work = null
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRequested = true
        super.onDestroy()
    }

    private suspend fun record(job: RecordJob) {
        val app = applicationContext as FeedVibeApp
        val settings = app.container.settings.current()
        if (settings.radioRecordWifiOnly && isMetered()) {
            notifyDone("Grabación no iniciada", "${job.stationName}: solo se graba con Wi‑Fi (Perfil → Radio)")
            return
        }
        // Siempre la máxima calidad disponible que se pueda grabar (no HLS).
        val streams = TuneIn.streams(job.stationId).filter { !it.isHls }
        val stream: Stream = streams.maxByOrNull { it.bitrate } ?: error("Esta emisora no se puede grabar (solo emite en HLS)")
        val dir = File(cacheDir, "rec").apply { mkdirs() }
        val raw = File(dir, "${job.id}.part")
        val started = System.currentTimeMillis()
        _state.value = RecordingState(job, started, 0, "Grabando")
        var contentType: String? = null
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).connectTimeout(20, TimeUnit.SECONDS).build()
        var failures = 0
        raw.outputStream().use { out ->
            // Si se corta la conexión se vuelve a conectar y se sigue en el mismo archivo.
            while (!stopRequested && (job.endAtMs == 0L || System.currentTimeMillis() < job.endAtMs)) {
                try {
                    client.newCall(Request.Builder().url(stream.url).header("User-Agent", "FeedVibe").build()).execute().use { resp ->
                        if (!resp.isSuccessful) error("La emisora respondió ${resp.code}")
                        contentType = contentType ?: resp.header("Content-Type")
                        val input = resp.body!!.byteStream()
                        val buf = ByteArray(16 * 1024)
                        var lastUi = 0L
                        while (!stopRequested && (job.endAtMs == 0L || System.currentTimeMillis() < job.endAtMs)) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 1000) {
                                lastUi = now
                                val bytes = raw.length()
                                _state.value = RecordingState(job, started, bytes, "Grabando")
                                updateNotification(job, started, bytes)
                            }
                        }
                    }
                    failures = 0
                } catch (e: Exception) {
                    if (stopRequested) break
                    if (++failures > 10) throw e
                    delay(3000L * failures)
                }
            }
        }
        if (raw.length() < 1024) { raw.delete(); error("No llegó audio de la emisora") }

        // Guardar en el formato elegido, con emisora, programa y logo.
        _state.value = RecordingState(job, started, raw.length(), "Guardando…")
        updateNotification(job, started, raw.length(), saving = true)
        val format = RecordFormat.of(settings.radioRecordFormat)
        val ext = when {
            contentType?.contains("aac", true) == true || stream.format.equals("aac", true) -> "aac"
            contentType?.contains("ogg", true) == true -> "ogg"
            else -> "mp3"
        }
        val day = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale("es")).format(Date(started))
        val title = job.program.ifBlank { "${job.stationName} $day" }
        val logo = job.image?.let { url -> runCatching { com.feedvibe.app.data.sources.Http.bytes(url) }.getOrNull() }
        val tags = AudioTags(
            title = title,
            artist = job.stationName,
            album = job.program.ifBlank { job.stationName },
            date = SimpleDateFormat("yyyy-MM-dd", Locale("es")).format(Date(started)),
            comment = "Grabado con FeedVibe · ${stream.bitrate} kbps",
            picture = logo,
            pictureMime = if (job.image?.contains(".png", true) == true) "image/png" else "image/jpeg",
        )
        val baseName = "${job.stationName} - ${title.takeIf { job.program.isNotBlank() } ?: ""} $day".replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim()
        val (file, name, mime) = when (format) {
            RecordFormat.ORIGINAL -> {
                val tagged = File(dir, "${job.id}.$ext")
                tagged.outputStream().use { o ->
                    if (ext != "ogg") o.write(Id3.build(tags))
                    raw.inputStream().use { it.copyTo(o) }
                }
                Triple(tagged, "$baseName.$ext", if (ext == "aac") "audio/aac" else if (ext == "ogg") "audio/ogg" else "audio/mpeg")
            }
            RecordFormat.FLAC -> Triple(Transcoder.toFlac(raw, File(dir, "${job.id}.flac"), tags), "$baseName.flac", "audio/flac")
            RecordFormat.WAV -> Triple(Transcoder.toWav(raw, File(dir, "${job.id}.wav")), "$baseName.wav", "audio/wav")
        }
        raw.delete()
        val where = Output.save(this, file, name, mime, settings.radioRecordFolder)
        file.delete()
        notifyDone("Grabación guardada", "$name · ${formatSize(where.second)} en ${where.first}")
    }

    private fun isMetered(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Grabaciones de radio", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String, job: RecordJob?): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(job?.let { "● Grabando ${it.stationName}" } ?: "Grabación de radio")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Parar y guardar", stop)
            .build()
    }

    private fun updateNotification(job: RecordJob, started: Long, bytes: Long, saving: Boolean = false) {
        val secs = (System.currentTimeMillis() - started) / 1000
        val time = String.format(Locale("es"), "%d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60)
        val text = if (saving) "Guardando la grabación…" else buildString {
            append(job.program.ifBlank { "En directo" }).append(" · ").append(time).append(" · ").append(formatSize(bytes))
            if (job.endAtMs > 0) append(" · termina a las ").append(SimpleDateFormat("HH:mm", Locale("es")).format(Date(job.endAtMs)))
        }
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, job)) }
    }

    private fun notifyDone(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify((System.currentTimeMillis() % 100000).toInt() + 6100, n) }
    }
}

/** Etiqueta ID3v2.3 (título, emisora, programa, fecha y logo) para MP3/AAC. */
object Id3 {
    fun build(t: AudioTags): ByteArray {
        val frames = java.io.ByteArrayOutputStream()
        fun frame(id: String, data: ByteArray) {
            frames.write(id.toByteArray())
            val n = data.size
            frames.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte(), 0, 0))
            frames.write(data)
        }
        fun text(id: String, v: String) = frame(id, byteArrayOf(1) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + v.toByteArray(Charsets.UTF_16LE))
        text("TIT2", t.title)
        text("TPE1", t.artist)
        text("TALB", t.album)
        text("TYER", t.date.take(4))
        text("TCON", "Radio")
        t.picture?.let { pic ->
            frame("APIC", byteArrayOf(0) + t.pictureMime.toByteArray() + byteArrayOf(0, 3, 0) + pic)
        }
        val body = frames.toByteArray()
        val size = body.size
        // Tamaño «sincsafe» (7 bits por byte).
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((size ushr 21) and 0x7F).toByte(), ((size ushr 14) and 0x7F).toByte(), ((size ushr 7) and 0x7F).toByte(), (size and 0x7F).toByte(),
        )
        return header + body
    }
}

/** Decodifica la emisión grabada (MP3/AAC) y la guarda en FLAC o WAV. */
object Transcoder {
    fun toFlac(src: File, dst: File, tags: AudioTags): File {
        var enc: FlacEncoder? = null
        decode(src) { pcm, frames, rate, ch ->
            val e = enc ?: FlacEncoder(dst, rate, ch, tags).also { enc = it }
            e.write(pcm, frames, ch)
        }
        (enc ?: error("No se pudo leer la grabación")).close()
        return dst
    }

    fun toWav(src: File, dst: File): File {
        var w: WavWriter? = null
        decode(src) { pcm, frames, rate, ch ->
            val e = w ?: WavWriter(dst, rate, ch).also { w = it }
            e.write(pcm, frames, ch)
        }
        (w ?: error("No se pudo leer la grabación")).close()
        return dst
    }

    private fun decode(src: File, sink: (ShortArray, Int, Int, Int) -> Unit) {
        val ex = MediaExtractor()
        ex.setDataSource(src.absolutePath)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: error("La grabación no tiene audio")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var shorts = ShortArray(0)
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        codec.outputFormat.let {
                            rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            ch = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                    o >= 0 -> {
                        if (info.size > 0) {
                            val sb = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val count = info.size / 2
                            if (shorts.size < count) shorts = ShortArray(count)
                            sb.position(info.offset / 2)
                            sb.get(shorts, 0, count)
                            sink(shorts, count / ch, rate, ch)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
    }
}

/** Dónde se guardan: la carpeta elegida o Música/FeedVibe. Devuelve (sitio, bytes). */
object Output {
    fun save(context: Context, file: File, name: String, mime: String, folder: String): Pair<String, Long> {
        val size = file.length()
        if (folder.isNotBlank()) {
            runCatching {
                val tree = Uri.parse(folder)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(context.contentResolver, parent, mime, name) ?: error("sin documento")
                context.contentResolver.openOutputStream(doc)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
                return Uri.decode(folder).substringAfterLast(':').ifBlank { "la carpeta elegida" } to size
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/FeedVibe")
            }
            val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: error("No se pudo crear el archivo")
            context.contentResolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
            return "Música/FeedVibe" to size
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "FeedVibe").apply { mkdirs() }
        file.copyTo(File(dir, name), overwrite = true)
        return dir.absolutePath to size
    }
}

/** Grabaciones programadas: una alarma exacta arranca la grabación a su hora. */
object RecordScheduler {
    fun schedule(context: Context, job: RecordJob) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, job)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, job.startAtMs, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, job.startAtMs, pi)
        }
    }

    fun cancel(context: Context, job: RecordJob) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, job))
    }

    private fun pending(context: Context, job: RecordJob) = PendingIntent.getBroadcast(
        context, (job.id % Int.MAX_VALUE).toInt(),
        Intent(context, RecordAlarmReceiver::class.java).putExtra("job", com.feedvibe.app.data.sources.AppJson.encodeToString(RecordJob.serializer(), job)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class RecordAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val job = intent.getStringExtra("job")?.let {
            runCatching { com.feedvibe.app.data.sources.AppJson.decodeFromString(RecordJob.serializer(), it) }.getOrNull()
        } ?: return
        RecordService.start(context, job)
        val app = context.applicationContext as FeedVibeApp
        app.container.appScope.launch { app.container.settings.editRadioSchedules { list -> list.filter { it.id != job.id } } }
    }
}

/** Descarga de episodios a la carta (respeta «solo con Wi‑Fi»). */
object EpisodeDownloader {
    suspend fun download(context: Context, ep: Episode, showName: String, wifiOnly: Boolean) {
        val url = TuneIn.streams(ep.id).first().url
        val ext = url.substringBefore('?').substringAfterLast('.', "mp3").take(4).lowercase().ifBlank { "mp3" }
        val name = "$showName - ${ep.title}".replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(120)
        val req = android.app.DownloadManager.Request(Uri.parse(url))
            .setTitle(ep.title)
            .setDescription(showName)
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(!wifiOnly)
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "FeedVibe/$name.$ext")
        context.getSystemService(android.app.DownloadManager::class.java).enqueue(req)
    }
}
