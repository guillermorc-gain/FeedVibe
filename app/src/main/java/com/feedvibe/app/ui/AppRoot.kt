package com.feedvibe.app.ui

import android.net.Uri
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

@Composable
fun AppRoot(settings: AppSettings, external: ExternalRequest?, onExternalHandled: () -> Unit) {
    val container = LocalContainer.current
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val unwatched by container.feeds.unwatchedCount.collectAsStateWithLifecycle(0)

    LaunchedEffect(external) {
        val req = external ?: return@LaunchedEffect
        req.sharedUrl?.let { nav.navigate(Routes.add(it)) }
        req.episodeId?.let { id ->
            val item = container.feeds.getEpisode(id)
            if (item != null) nav.navigate(Routes.channel(item.episode.subscriptionId))
        }
        onExternalHandled()
    }

    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = route == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = {
                                BadgedBox(badge = {
                                    if (tab.route == Routes.FEED && unwatched > 0) {
                                        Badge { Text(if (unwatched > 99) "99+" else unwatched.toString()) }
                                    }
                                }) { Icon(if (selected) tab.selectedIcon else tab.icon, tab.label) }
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.FEED, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable(Routes.FEED) { FeedScreen(nav, settings) }
            composable(Routes.CHANNELS) { ChannelsScreen(nav, settings) }
            composable(Routes.LIBRARY) { LibraryScreen(nav, settings) }
            composable(Routes.PROFILE) { ProfileScreen(nav, settings) }
            composable(Routes.CHANNEL, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                ChannelDetailScreen(nav, settings, it.arguments?.getString("id").orEmpty())
            }
            composable(Routes.ADD, arguments = listOf(navArgument("url") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                AddFeedScreen(nav, it.arguments?.getString("url"))
            }
            composable(Routes.PLAYER, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                PlayerScreen(nav, it.arguments?.getString("id").orEmpty())
            }
            composable(Routes.APPEARANCE) { AppearanceScreen(nav, settings) }
            composable(Routes.SYNC) { SyncScreen(nav, settings) }
            composable(Routes.BACKUP) { BackupScreen(nav, settings) }
            composable(Routes.NOTIFICATIONS) { NotificationsScreen(nav, settings) }
            composable(Routes.PLAYBACK) { PlaybackScreen(nav, settings) }
            composable(Routes.ABOUT) { AboutScreen(nav, settings) }
            composable(Routes.IMPORT_OPML) { ImportOpmlScreen(nav) }
        }
    }

    UpdateDialog()
}

fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
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
