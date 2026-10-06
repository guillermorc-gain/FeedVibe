package com.feedvibe.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Pellizcar con dos dedos para agrandar o reducir (como en Podcast Addict). Un solo dedo
 * sigue haciendo scroll normal; [onEnd] se llama al soltar para guardar el tamaño.
 */
@Composable
fun Modifier.pinchToZoom(onZoom: (Float) -> Unit, onEnd: () -> Unit): Modifier {
    // Siempre las lambdas más recientes, sin reiniciar el detector de gestos en cada recomposición.
    val zoomCb by rememberUpdatedState(onZoom)
    val endCb by rememberUpdatedState(onEnd)
    return pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoomed = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) {
                    zoomCb(zoom)
                    zoomed = true
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
        if (zoomed) endCb()
    }
    }
}
