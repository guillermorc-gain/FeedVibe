package com.feedvibe.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight

/** Scaffold con barra superior; los insets inferiores los gestiona AppRoot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null,
    /** Si no es null sustituye a la barra superior (p. ej. la barra de selección múltiple). */
    topBarOverride: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = floatingActionButton,
        topBar = {
            if (topBarOverride != null) {
                topBarOverride()
            } else {
                TopAppBar(
                    title = { if (titleContent != null) titleContent() else Text(title, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
                        }
                    },
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        content = content,
    )
}
