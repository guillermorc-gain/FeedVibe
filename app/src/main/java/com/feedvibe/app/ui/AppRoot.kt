package com.feedvibe.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavController
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.feedvibe.app.ExternalRequest
import com.feedvibe.app.data.prefs.AppSettings
import com.feedvibe.app.ui.screens.AboutScreen
import com.feedvibe.app.ui.screens.AddFeedScreen
import com.feedvibe.app.ui.screens.AppearanceScreen
import com.feedvibe.app.ui.screens.BackupScreen
import com.feedvibe.app.ui.screens.ChannelDetailScreen
import com.feedvibe.app.ui.screens.ChannelsScreen
import com.feedvibe.app.ui.screens.FeedScreen
import com.feedvibe.app.ui.screens.ImportOpmlScreen
import com.feedvibe.app.ui.screens.LibraryScreen
import com.feedvibe.app.ui.screens.NotificationsScreen
import com.feedvibe.app.ui.screens.PlaybackScreen
import com.feedvibe.app.ui.screens.PlayerScreen
import com.feedvibe.app.ui.screens.ProfileScreen
import com.feedvibe.app.ui.screens.SyncScreen
import com.feedvibe.app.update.UpdateState
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val FEED = "feed"
    const val CHANNELS = "channels"
    const val LIBRARY = "library"
    const val PROFILE = "profile"
    const val CHANNEL = "channel/{id}"
    const val ADD = "add?url={url}"
    const val PLAYER = "player/{id}"
    const val APPEARANCE = "settings/appearance"
    const val SYNC = "settings/sync"
    const val BACKUP = "settings/backup"
    const val NOTIFICATIONS = "settings/notifications"
    const val PLAYBACK = "settings/playback"
    const val ABOUT = "settings/about"
    const val IMPORT_OPML = "import/opml"

    fun channel(id: String) = "channel/$id"
    fun add(url: String? = null) = if (url == null) "add" else "add?url=${Uri.encode(url)}"
    fun player(id: String) = "player/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(Routes.FEED, "Novedades", Icons.Outlined.NewReleases, Icons.Filled.NewReleases),
    Tab(Routes.CHANNELS, "Canales", Icons.Outlined.Subscriptions, Icons.Filled.Subscriptions),
    Tab(Routes.LIBRARY, "Biblioteca", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
    Tab(Routes.PROFILE, "Perfil", Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle),
)

/** Petición de cambio de pestaña desde otras pantallas (p. ej. «Ver mis canales»). */
object HomeTabs {
    val requested = mutableStateOf<Int?>(null)
    fun indexOf(route: String) = tabs.indexOfFirst { it.route == route }.coerceAtLeast(0)
}

@Composable
fun AppRoot(settings: AppSettings, external: ExternalRequest?, onExternalHandled: () -> Unit) {
    val container = LocalContainer.current
    val nav = rememberNavController()
    val context = LocalContext.current

    // Permiso de notificaciones (Android 13+): avisos de episodios nuevos y número en el icono.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(external) {
        val req = external ?: return@LaunchedEffect
        req.sharedUrl?.let { nav.navigate(Routes.add(it)) }
        req.episodeId?.let { id ->
            val item = container.feeds.getEpisode(id)
            if (item != null) nav.navigate(Routes.channel(item.episode.subscriptionId))
        }
        onExternalHandled()
    }

    NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize()) {
        composable(Routes.HOME) { HomeScreen(nav, settings) }
        composable(Routes.CHANNEL, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            Detail { ChannelDetailScreen(nav, settings, it.arguments?.getString("id").orEmpty()) }
        }
        composable(Routes.ADD, arguments = listOf(navArgument("url") { type = NavType.StringType; nullable = true; defaultValue = null })) {
            Detail { AddFeedScreen(nav, it.arguments?.getString("url")) }
        }
        composable(Routes.PLAYER, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            Detail { PlayerScreen(nav, it.arguments?.getString("id").orEmpty()) }
        }
        composable(Routes.APPEARANCE) { Detail { AppearanceScreen(nav, settings) } }
        composable(Routes.SYNC) { Detail { SyncScreen(nav, settings) } }
        composable(Routes.BACKUP) { Detail { BackupScreen(nav, settings) } }
        composable(Routes.NOTIFICATIONS) { Detail { NotificationsScreen(nav, settings) } }
        composable(Routes.PLAYBACK) { Detail { PlaybackScreen(nav, settings) } }
        composable(Routes.ABOUT) { Detail { AboutScreen(nav, settings) } }
        composable(Routes.IMPORT_OPML) { Detail { ImportOpmlScreen(nav) } }
    }

    UpdateDialog()
}

/** Pantallas secundarias: dejan sitio a la barra de navegación del sistema. */
@Composable
private fun Detail(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().navigationBarsPadding()) { content() }
}

/**
 * Las 4 pestañas en un carrusel: se cambia deslizando a los lados y da la vuelta
 * (de Perfil se pasa a Novedades y al revés).
 */
@Composable
private fun HomeScreen(nav: NavHostController, settings: AppSettings) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val unwatched by container.feeds.unwatchedCount.collectAsStateWithLifecycle(0)
    val start = remember { val half = Int.MAX_VALUE / 2; half - half % tabs.size }
    val pager = rememberPagerState(initialPage = start) { Int.MAX_VALUE }
    val current = Math.floorMod(pager.currentPage, tabs.size)

    fun goTo(index: Int) {
        var diff = index - current
        if (diff > tabs.size / 2) diff -= tabs.size
        if (diff < -tabs.size / 2) diff += tabs.size
        scope.launch { pager.animateScrollToPage(pager.currentPage + diff) }
    }

    val requested by HomeTabs.requested
    LaunchedEffect(requested) {
        val r = requested ?: return@LaunchedEffect
        goTo(r)
        HomeTabs.requested.value = null
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == current
                    NavigationBarItem(
                        selected = selected,
                        onClick = { goTo(index) },
                        icon = {
                            BadgedBox(badge = {
                                if (tab.route == Routes.FEED && unwatched > 0) {
                                    Badge { Text(if (unwatched > 999) "999+" else unwatched.toString()) }
                                }
                            }) { Icon(if (selected) tab.selectedIcon else tab.icon, tab.label) }
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()),
            beyondViewportPageCount = 0,
        ) { page ->
            when (Math.floorMod(page, tabs.size)) {
                0 -> FeedScreen(nav, settings)
                1 -> ChannelsScreen(nav, settings)
                2 -> LibraryScreen(nav, settings)
                else -> ProfileScreen(nav, settings)
            }
        }
    }
}

/** Vuelve a la pantalla principal y cambia a la pestaña indicada. */
fun NavController.navigateTab(route: String) {
    HomeTabs.requested.value = HomeTabs.indexOf(route)
    if (!popBackStack(Routes.HOME, inclusive = false)) navigate(Routes.HOME)
}

/** Diálogo de actualización disponible / descargando (sin salir de la app). */
@Composable
fun UpdateDialog() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val state by container.updater.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { container.updater.dismiss() },
            title = { Text("Nueva versión ${s.release.version}") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text("Tienes la ${container.updater.currentVersion}. Se descargará e instalará sin salir de FeedVibe.")
                    if (s.release.notes.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Novedades", style = MaterialTheme.typography.titleSmall)
                        Text(s.release.notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!container.updater.canInstall()) {
                        context.startActivity(container.updater.unknownSourcesIntent())
                    } else {
                        container.appScope.launch { container.updater.downloadAndInstall(s.release) }
                    }
                }) { Text(if (container.updater.canInstall()) "Actualizar" else "Permitir instalación") }
            },
            dismissButton = { TextButton(onClick = { container.updater.dismiss() }) { Text("Más tarde") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Descargando ${s.release.version}") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(s.progress * 100).toInt()} %")
                }
            },
            confirmButton = {},
        )
        is UpdateState.Installing -> AlertDialog(
            onDismissRequest = { container.updater.dismiss() },
            title = { Text("Instalando…") },
            text = { Text("Confirma la instalación en el aviso de Android. La app se reiniciará con la nueva versión.") },
            confirmButton = { TextButton(onClick = { container.updater.dismiss() }) { Text("Aceptar") } },
        )
        else -> Unit
    }
}
