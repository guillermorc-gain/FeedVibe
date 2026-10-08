package com.feedvibe.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.feedvibe.app.data.prefs.AppSettings
import com.feedvibe.app.ui.LocalContainer
import com.feedvibe.app.ui.Routes
import com.feedvibe.app.ui.orderedTabs
import kotlinx.coroutines.launch

/** Ordenar las pestañas de abajo. */
@Composable
fun TabsScreen(nav: NavController, settings: AppSettings) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val tabs = orderedTabs(settings.tabOrder)

    fun move(from: Int, to: Int) {
        if (to !in tabs.indices) return
        val list = tabs.toMutableList()
        list.add(to, list.removeAt(from))
        scope.launch { container.settings.update { it.copy(tabOrder = list.joinToString(",") { t -> t.route }) } }
    }

    SettingsPage(nav, "Pestañas") {
        Text(
            "Ordena las pestañas con las flechas. La de Radio solo aparece en modo radio (toca el título de la app).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        SettingsGroup {
            tabs.forEachIndexed { i, tab ->
                ListItem(
                    headlineContent = { Text(tab.label) },
                    supportingContent = if (tab.route == Routes.RADIO && !settings.radioMode) { { Text("Oculta: activa el modo radio") } } else null,
                    leadingContent = { Icon(tab.selectedIcon, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { move(i, i - 1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Subir") }
                            IconButton(onClick = { move(i, i + 1) }, enabled = i < tabs.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "Bajar") }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}
