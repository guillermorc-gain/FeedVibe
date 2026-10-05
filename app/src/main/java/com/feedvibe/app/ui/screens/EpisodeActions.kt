package com.feedvibe.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.Routes
import com.feedvibe.app.ui.components.EpisodeCallbacks
import com.feedvibe.app.ui.openEpisode
import com.feedvibe.app.ui.shareText
import kotlinx.coroutines.launch

@Composable
fun rememberEpisodeCallbacks(nav: NavController, allowOpenChannel: Boolean = true): EpisodeCallbacks {
    val container = LocalContainer.current
    val context = LocalContext.current
    return remember(nav, allowOpenChannel) {
        val feeds = container.feeds
        val scope = container.appScope
        EpisodeCallbacks(
            onOpen = { item -> openEpisode(context, container, item) { id -> nav.navigate(Routes.player(id)) } },
            onToggleWatched = { scope.launch { feeds.toggleWatched(it) } },
            onToggleWatchLater = { scope.launch { feeds.toggleWatchLater(it) } },
            onToggleFavorite = { scope.launch { feeds.toggleFavorite(it) } },
            onMarkOlder = { scope.launch { feeds.markOlderWatched(it) } },
            onShare = { shareText(context, it.episode.title, it.episode.url) },
            onOpenChannel = if (allowOpenChannel) { item -> nav.navigate(Routes.channel(item.episode.subscriptionId)) } else null,
        )
    }
}
