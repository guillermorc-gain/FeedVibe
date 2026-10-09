package com.feedvibe.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private val Context.dataStore by preferencesDataStore("settings")

enum class ThemeMode(val label: String) { SYSTEM("Sistema"), LIGHT("Claro"), DARK("Oscuro") }

enum class OpenMode(val label: String) { EXTERNAL("App externa"), INTERNAL("Navegador integrado") }

enum class ListStyle(val label: String) { CARDS("Tarjetas"), COMPACT("Compacta") }

/** Colores de acento disponibles (como en el selector de apariencia de EMT Palma). */
enum class AccentColor(val label: String, val argb: Long) {
    BLUE("Azul", 0xFF0A7CF6),
    RED("Rojo", 0xFFE53935),
    ORANGE("Naranja", 0xFFF57C00),
    GREEN("Verde", 0xFF2E7D32),
    TEAL("Turquesa", 0xFF00897B),
    PURPLE("Morado", 0xFF7E57C2),
    PINK("Rosa", 0xFFD81B60),
    BROWN("Marrón", 0xFF6D4C41),
}

/** Opciones de periodo de sincronización/actualización automática. */
val SYNC_INTERVALS: List<Pair<Int, String>> = listOf(
    0 to "Manual",
    15 to "Cada 15 minutos",
    30 to "Cada 30 minutos",
    60 to "Cada hora",
    120 to "Cada 2 horas",
    360 to "Cada 6 horas",
    720 to "Cada 12 horas",
    1440 to "Una vez al día",
)

val BACKUP_INTERVALS: List<Pair<Int, String>> = listOf(
    0 to "Desactivada",
    1 to "Diaria",
    7 to "Semanal",
    30 to "Mensual",
)

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val accent: AccentColor = AccentColor.BLUE,
    val amoled: Boolean = false,
    val listStyle: ListStyle = ListStyle.CARDS,
    val syncIntervalMin: Int = 60,
    val wifiOnly: Boolean = false,
    val refreshOnOpen: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val notifyLive: Boolean = true,
    val openMode: OpenMode = OpenMode.EXTERNAL,
    val hideShorts: Boolean = false,
    /** Al terminar un vídeo en el reproductor, pasar solo al siguiente sin ver. */
    val autoplayNext: Boolean = false,
    val hideWatched: Boolean = true,
    val autoBackupDays: Int = 7,
    val backupKeep: Int = 10,
    val autoUpdateCheck: Boolean = true,
    val nickname: String = "",
    /** Clave de la API de YouTube Data v3 (para cargar el historial completo de un canal). */
    val youtubeApiKey: String = "",
    /** Orden de la pantalla de canales (nombre de [ChannelSort]). */
    val channelSort: String = "NAME",
    /** Tamaño de los logos en la cuadrícula de canales, en dp (como el zoom de Podcast Addict). */
    val channelGridSize: Int = 104,
    val channelGrid: Boolean = true,
    val showChannelNames: Boolean = true,
    /** No mostrar la foto de la cuenta de Google como foto de perfil. */
    val hideGooglePhoto: Boolean = false,
    /** Orden en Novedades: false = más recientes primero, true = más antiguos primero. */
    val feedOldestFirst: Boolean = false,
    /** Orden dentro de cada canal. */
    val channelOldestFirst: Boolean = false,
    /** Dentro de un canal, mostrar solo los episodios sin ver. */
    val channelHideWatched: Boolean = false,
    /** Deslizar un episodio a los lados lo marca (si no, el deslizamiento cambia de pestaña). */
    val swipeToMark: Boolean = false,
    /** Número de episodios sin ver en el icono de la app. */
    val iconBadge: Boolean = true,
    /** Ancho mínimo de las tarjetas de Novedades (pellizcar para cambiarlo). */
    val feedCardWidth: Int = 360,
    /** Pantalla Canales: mostrar solo los que tienen episodios sin ver. */
    val channelsOnlyUnwatched: Boolean = true,
    /** Orden de las pestañas (rutas separadas por comas). */
    val tabOrder: String = "feed,channels,radio,library,profile",
    /** Modo radio (se activa tocando el título): aparece la pestaña Radio. */
    val radioMode: Boolean = false,
    /** Calidad al escuchar la radio: MAX (máxima) o SAVER (ahorro de datos). */
    val radioQuality: String = "MAX",
    /** Carpeta para las grabaciones (URI del árbol elegido); vacío = Música/FeedVibe. */
    val radioRecordFolder: String = "",
    /** Grabar solo con Wi‑Fi. */
    val radioRecordWifiOnly: Boolean = false,
    /** Formato de las grabaciones: ORIGINAL (MP3/AAC tal cual), FLAC o WAV. */
    val radioRecordFormat: String = "FLAC",
    /** Últimos filtros de la radio: comunidad. */
    val radioState: String = "",
    /** Últimos filtros de la radio: estilo. */
    val radioTag: String = "",
)

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic")
        val accent = stringPreferencesKey("accent")
        val amoled = booleanPreferencesKey("amoled")
        val listStyle = stringPreferencesKey("list_style")
        val syncInterval = intPreferencesKey("sync_interval")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val refreshOnOpen = booleanPreferencesKey("refresh_on_open")
        val notifications = booleanPreferencesKey("notifications")
        val notifyLive = booleanPreferencesKey("notify_live")
        val openMode = stringPreferencesKey("open_mode")
        val hideShorts = booleanPreferencesKey("hide_shorts")
        val autoplayNext = booleanPreferencesKey("autoplay_next")
        val hideWatched = booleanPreferencesKey("hide_watched")
        val autoBackup = intPreferencesKey("auto_backup_days")
        val backupKeep = intPreferencesKey("backup_keep")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val nickname = stringPreferencesKey("nickname")
        val youtubeApiKey = stringPreferencesKey("youtube_api_key")
        val channelSort = stringPreferencesKey("channel_sort")
        val channelGridSize = intPreferencesKey("channel_grid_size")
        val channelGrid = booleanPreferencesKey("channel_grid")
        val showChannelNames = booleanPreferencesKey("show_channel_names")
        val hideGooglePhoto = booleanPreferencesKey("hide_google_photo")
        val feedOldestFirst = booleanPreferencesKey("feed_oldest_first")
        val channelOldestFirst = booleanPreferencesKey("channel_oldest_first")
        val channelHideWatched = booleanPreferencesKey("channel_hide_watched")
        val swipeToMark = booleanPreferencesKey("swipe_to_mark")
        val iconBadge = booleanPreferencesKey("icon_badge")
        val feedCardWidth = intPreferencesKey("feed_card_width")
        val backfillV2 = booleanPreferencesKey("backfill_v2_done")
        val repairWatchedV1 = booleanPreferencesKey("repair_watched_v1_done")
        val repairReload = booleanPreferencesKey("repair_reload_v1_done")
        val channelsOnlyUnwatched = booleanPreferencesKey("channels_only_unwatched")
        val tabOrder = stringPreferencesKey("tab_order")
        val radioMode = booleanPreferencesKey("radio_mode")
        val radioQuality = stringPreferencesKey("radio_quality")
        val radioRecordFolder = stringPreferencesKey("radio_record_folder")
        val radioRecordWifiOnly = booleanPreferencesKey("radio_record_wifi_only")
        val radioRecordFormat = stringPreferencesKey("radio_record_format")
        val radioSchedules = stringPreferencesKey("radio_schedules")
        val radioFavorites = stringPreferencesKey("radio_favorites")
        val radioState = stringPreferencesKey("radio_state")
        val radioTag = stringPreferencesKey("radio_tag")

        val lastRefresh = longPreferencesKey("last_refresh")
        val lastBackup = longPreferencesKey("last_backup")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
        val stateCursor = longPreferencesKey("channel_cursor")
        // v4: se añaden los datos de los episodios de la Biblioteca; se repite la primera sincronización.
        val syncedUid = stringPreferencesKey("synced_uid_v4")
        val firestoreQueueDropped = booleanPreferencesKey("firestore_queue_dropped")
        val accessCache = stringPreferencesKey("access_cache")
        val appClosed = booleanPreferencesKey("app_closed")
        val radioRecents = stringPreferencesKey("radio_recents")
        val radioFavoritesAt = longPreferencesKey("radio_favorites_at")
        val profilePhotoVersion = longPreferencesKey("profile_photo_version")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = p.enum(K.theme, d.themeMode),
            dynamicColor = p[K.dynamic] ?: d.dynamicColor,
            accent = p.enum(K.accent, d.accent),
            amoled = p[K.amoled] ?: d.amoled,
            listStyle = p.enum(K.listStyle, d.listStyle),
            syncIntervalMin = p[K.syncInterval] ?: d.syncIntervalMin,
            wifiOnly = p[K.wifiOnly] ?: d.wifiOnly,
            refreshOnOpen = p[K.refreshOnOpen] ?: d.refreshOnOpen,
            notificationsEnabled = p[K.notifications] ?: d.notificationsEnabled,
            notifyLive = p[K.notifyLive] ?: d.notifyLive,
            openMode = p.enum(K.openMode, d.openMode),
            hideShorts = p[K.hideShorts] ?: d.hideShorts,
            autoplayNext = p[K.autoplayNext] ?: d.autoplayNext,
            hideWatched = p[K.hideWatched] ?: d.hideWatched,
            autoBackupDays = p[K.autoBackup] ?: d.autoBackupDays,
            backupKeep = p[K.backupKeep] ?: d.backupKeep,
            autoUpdateCheck = p[K.autoUpdate] ?: d.autoUpdateCheck,
            nickname = p[K.nickname] ?: d.nickname,
            youtubeApiKey = p[K.youtubeApiKey] ?: d.youtubeApiKey,
            channelSort = p[K.channelSort] ?: d.channelSort,
            channelGridSize = p[K.channelGridSize] ?: d.channelGridSize,
            channelGrid = p[K.channelGrid] ?: d.channelGrid,
            showChannelNames = p[K.showChannelNames] ?: d.showChannelNames,
            hideGooglePhoto = p[K.hideGooglePhoto] ?: d.hideGooglePhoto,
            feedOldestFirst = p[K.feedOldestFirst] ?: d.feedOldestFirst,
            channelOldestFirst = p[K.channelOldestFirst] ?: d.channelOldestFirst,
            channelHideWatched = p[K.channelHideWatched] ?: d.channelHideWatched,
            swipeToMark = p[K.swipeToMark] ?: d.swipeToMark,
            iconBadge = p[K.iconBadge] ?: d.iconBadge,
            feedCardWidth = p[K.feedCardWidth] ?: d.feedCardWidth,
            channelsOnlyUnwatched = p[K.channelsOnlyUnwatched] ?: d.channelsOnlyUnwatched,
            tabOrder = p[K.tabOrder] ?: d.tabOrder,
            radioMode = p[K.radioMode] ?: d.radioMode,
            radioQuality = p[K.radioQuality] ?: d.radioQuality,
            radioRecordFolder = p[K.radioRecordFolder] ?: d.radioRecordFolder,
            radioRecordWifiOnly = p[K.radioRecordWifiOnly] ?: d.radioRecordWifiOnly,
            radioRecordFormat = p[K.radioRecordFormat] ?: d.radioRecordFormat,
            radioState = p[K.radioState] ?: d.radioState,
            radioTag = p[K.radioTag] ?: d.radioTag,
        )
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { read(it) }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val n = transform(read(p))
            p[K.theme] = n.themeMode.name
            p[K.dynamic] = n.dynamicColor
            p[K.accent] = n.accent.name
            p[K.amoled] = n.amoled
            p[K.listStyle] = n.listStyle.name
            p[K.syncInterval] = n.syncIntervalMin
            p[K.wifiOnly] = n.wifiOnly
            p[K.refreshOnOpen] = n.refreshOnOpen
            p[K.notifications] = n.notificationsEnabled
            p[K.notifyLive] = n.notifyLive
            p[K.openMode] = n.openMode.name
            p[K.hideShorts] = n.hideShorts
            p[K.autoplayNext] = n.autoplayNext
            p[K.hideWatched] = n.hideWatched
            p[K.autoBackup] = n.autoBackupDays
            p[K.backupKeep] = n.backupKeep
            p[K.autoUpdate] = n.autoUpdateCheck
            p[K.nickname] = n.nickname
            p[K.youtubeApiKey] = n.youtubeApiKey
            p[K.channelSort] = n.channelSort
            p[K.channelGridSize] = n.channelGridSize
            p[K.channelGrid] = n.channelGrid
            p[K.showChannelNames] = n.showChannelNames
            p[K.hideGooglePhoto] = n.hideGooglePhoto
            p[K.feedOldestFirst] = n.feedOldestFirst
            p[K.channelOldestFirst] = n.channelOldestFirst
            p[K.channelHideWatched] = n.channelHideWatched
            p[K.swipeToMark] = n.swipeToMark
            p[K.iconBadge] = n.iconBadge
            p[K.feedCardWidth] = n.feedCardWidth
            p[K.channelsOnlyUnwatched] = n.channelsOnlyUnwatched
            p[K.tabOrder] = n.tabOrder
            p[K.radioMode] = n.radioMode
            p[K.radioQuality] = n.radioQuality
            p[K.radioRecordFolder] = n.radioRecordFolder
            p[K.radioRecordWifiOnly] = n.radioRecordWifiOnly
            p[K.radioRecordFormat] = n.radioRecordFormat
            p[K.radioState] = n.radioState
            p[K.radioTag] = n.radioTag
        }
    }

    // ---- Valores internos ----
    private fun longFlow(key: Preferences.Key<Long>): Flow<Long> = context.dataStore.data.map { it[key] ?: 0L }
    private suspend fun setLong(key: Preferences.Key<Long>, v: Long) = context.dataStore.edit { it[key] = v }

    val lastRefresh: Flow<Long> = longFlow(K.lastRefresh)
    suspend fun setLastRefresh(v: Long) = setLong(K.lastRefresh, v)
    val lastBackup: Flow<Long> = longFlow(K.lastBackup)
    suspend fun setLastBackup(v: Long) = setLong(K.lastBackup, v)
    suspend fun lastUpdateCheck(): Long = longFlow(K.lastUpdateCheck).first()
    suspend fun setLastUpdateCheck(v: Long) = setLong(K.lastUpdateCheck, v)
    suspend fun stateCursor(): Long = longFlow(K.stateCursor).first()
    suspend fun setStateCursor(v: Long) = setLong(K.stateCursor, v)
    val profilePhotoVersion: Flow<Long> = longFlow(K.profilePhotoVersion)
    suspend fun bumpProfilePhoto() = setLong(K.profilePhotoVersion, System.currentTimeMillis())

    suspend fun firestoreQueueDropped(): Boolean = context.dataStore.data.first()[K.firestoreQueueDropped] == true
    suspend fun setFirestoreQueueDropped() = context.dataStore.edit { it[K.firestoreQueueDropped] = true }
    // ---------- Radio: favoritas y escuchadas hace poco ----------

    private fun stations(json: String?): List<com.feedvibe.app.data.radio.Station> =
        if (json.isNullOrBlank()) emptyList()
        else runCatching { com.feedvibe.app.data.sources.AppJson.decodeFromString<List<com.feedvibe.app.data.radio.Station>>(json) }.getOrDefault(emptyList())

    private fun stationsJson(list: List<com.feedvibe.app.data.radio.Station>) =
        com.feedvibe.app.data.sources.AppJson.encodeToString(list)

    val radioFavorites: Flow<List<com.feedvibe.app.data.radio.Station>> = context.dataStore.data.map { stations(it[K.radioFavorites]) }
    val radioRecents: Flow<List<com.feedvibe.app.data.radio.Station>> = context.dataStore.data.map { stations(it[K.radioRecents]) }

    /** Cambia una favorita y devuelve (lista en JSON, hora del cambio) para sincronizarla. */
    suspend fun toggleFavoriteStation(s: com.feedvibe.app.data.radio.Station): Pair<String, Long> {
        var result = "" to 0L
        context.dataStore.edit { p ->
            val list = stations(p[K.radioFavorites])
            val json = stationsJson(if (list.any { it.id == s.id }) list.filter { it.id != s.id } else list + s)
            val now = System.currentTimeMillis()
            p[K.radioFavorites] = json
            p[K.radioFavoritesAt] = now
            result = json to now
        }
        return result
    }

    suspend fun radioFavoritesSnapshot(): Pair<String, Long> = context.dataStore.data.first().let {
        (it[K.radioFavorites] ?: "[]") to (it[K.radioFavoritesAt] ?: 0L)
    }

    /** Favoritas que llegan de otro dispositivo: solo si son más recientes. */
    suspend fun applyRemoteRadioFavorites(json: String, at: Long) = context.dataStore.edit { p ->
        if (at > (p[K.radioFavoritesAt] ?: 0L)) {
            p[K.radioFavorites] = json
            p[K.radioFavoritesAt] = at
        }
    }

    /** Grabaciones programadas. */
    val radioSchedules: Flow<List<com.feedvibe.app.radio.RecordJob>> = context.dataStore.data.map { p ->
        p[K.radioSchedules]?.let { runCatching { com.feedvibe.app.data.sources.AppJson.decodeFromString<List<com.feedvibe.app.radio.RecordJob>>(it) }.getOrNull() }.orEmpty()
    }

    suspend fun editRadioSchedules(change: (List<com.feedvibe.app.radio.RecordJob>) -> List<com.feedvibe.app.radio.RecordJob>) = context.dataStore.edit { p ->
        val list = p[K.radioSchedules]?.let { runCatching { com.feedvibe.app.data.sources.AppJson.decodeFromString<List<com.feedvibe.app.radio.RecordJob>>(it) }.getOrNull() }.orEmpty()
        p[K.radioSchedules] = com.feedvibe.app.data.sources.AppJson.encodeToString(change(list))
    }

    suspend fun addRecentStation(s: com.feedvibe.app.data.radio.Station) = context.dataStore.edit { p ->
        p[K.radioRecents] = stationsJson((listOf(s) + stations(p[K.radioRecents]).filter { it.id != s.id }).take(20))
    }

    /** La app se cerró del todo: no se muestran avisos ni número en el icono hasta abrirla. */
    val appClosed: Flow<Boolean> = context.dataStore.data.map { it[K.appClosed] == true }
    suspend fun setAppClosed(v: Boolean) = context.dataStore.edit { it[K.appClosed] = v }

    /** Última decisión de acceso: "*" si la app es pública o el correo autorizado. */
    suspend fun accessCache(): String? = context.dataStore.data.first()[K.accessCache]
    suspend fun setAccessCache(v: String?) = context.dataStore.edit {
        if (v == null) it.remove(K.accessCache) else it[K.accessCache] = v
    }
    suspend fun syncedUid(): String? = context.dataStore.data.first()[K.syncedUid]
    suspend fun setSyncedUid(uid: String?) = context.dataStore.edit {
        if (uid == null) it.remove(K.syncedUid) else it[K.syncedUid] = uid
    }

    suspend fun backfillDone(): Boolean = context.dataStore.data.first()[K.backfillV2] ?: false
    suspend fun setBackfillDone() = context.dataStore.edit { it[K.backfillV2] = true }
    suspend fun repairWatchedDone(): Boolean = context.dataStore.data.first()[K.repairWatchedV1] ?: false
    suspend fun setRepairWatchedDone() = context.dataStore.edit { it[K.repairWatchedV1] = true }
    suspend fun repairReloadDone(): Boolean = context.dataStore.data.first()[K.repairReload] ?: false
    suspend fun setRepairReloadDone() = context.dataStore.edit { it[K.repairReload] = true }

    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[K.onboardingDone] ?: false }
    suspend fun setOnboardingDone() = context.dataStore.edit { it[K.onboardingDone] = true }
}
