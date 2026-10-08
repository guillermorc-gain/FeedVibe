package com.feedvibe.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.feedvibe.app.R

/** Colores sacados del icono de la app. */
private object BrandColors {
    val blueLight = Color(0xFF00A3FF)
    val blue = Color(0xFF0A7CF6)
    val blueDeep = Color(0xFF005CF3)
    val orange = Color(0xFFFE8703)
    val red = Color(0xFFFC212B)
    val purple = Color(0xFF7B17F4)
    val yellow = Color(0xFFFFE201)
}

/**
 * «FeedVibe» con el icono y los degradados del logotipo.
 * @param refreshing buscando episodios nuevos: una luz del color del tema palpita detrás.
 * @param syncing sincronizando con otros dispositivos: caen flechas hacia el título.
 */
@Composable
fun BrandTitle(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 26.sp,
    showIcon: Boolean = true,
    refreshing: Boolean = false,
    syncing: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "actividad")
    // Luz que palpita (solo se usa mientras se buscan episodios).
    val pulse by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulso",
    )
    // Avance de la lluvia de flechas (0 → 1 en bucle).
    val rain by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "lluvia",
    )
    val effects = Modifier.drawBehind {
        if (refreshing) {
            // Halo ovalado detrás de las letras.
            val w = size.width * 1.15f
            val h = size.height * 2.2f
            scale(scaleX = 1f, scaleY = h / w, pivot = center) {
                drawCircle(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = pulse), accent.copy(alpha = pulse * 0.35f), Color.Transparent),
                        center = center,
                        radius = w / 2,
                    ),
                    radius = w / 2,
                    center = center,
                )
            }
        } else if (syncing) {
            val arrows = 7
            val stroke = 2.dp.toPx()
            val len = size.height * 0.45f
            val head = len * 0.35f
            for (i in 0 until arrows) {
                // Cada flecha cae con un desfase distinto, de arriba hacia el título.
                val phase = (rain + i * 0.37f) % 1f
                val x = size.width * (i + 0.5f) / arrows + ((i * 13) % 7 - 3) * 2f
                val y = -size.height * 0.9f + phase * size.height * 1.4f
                val alpha = kotlin.math.sin(phase * Math.PI).toFloat() * 0.9f
                val c = accent.copy(alpha = alpha)
                drawLine(c, Offset(x, y - len), Offset(x, y), stroke, StrokeCap.Round)
                drawLine(c, Offset(x - head, y - head), Offset(x, y), stroke, StrokeCap.Round)
                drawLine(c, Offset(x + head, y - head), Offset(x, y), stroke, StrokeCap.Round)
            }
        }
    }
    Box(modifier.then(effects), contentAlignment = Alignment.Center) {
        BrandLogo(fontSize = fontSize, showIcon = showIcon)
    }
}

@Composable
private fun BrandLogo(fontSize: TextUnit, showIcon: Boolean) {
    val modifier = Modifier
    val context = LocalContext.current
    val icon = remember {
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(128, 128)?.asImageBitmap()
    }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // En modo oscuro el azul profundo del icono apenas se ve: se aclara el degradado.
    val feedBrush = Brush.linearGradient(
        if (dark) listOf(Color(0xFF6CCBFF), BrandColors.blueLight, BrandColors.blue)
        else listOf(BrandColors.blueLight, BrandColors.blue, BrandColors.blueDeep)
    )
    val vibeBrush = Brush.linearGradient(listOf(BrandColors.orange, BrandColors.red, BrandColors.purple))
    val glow = Shadow(
        color = (if (dark) BrandColors.blueLight else BrandColors.blueDeep).copy(alpha = 0.28f),
        offset = Offset(0f, 3f),
        blurRadius = 8f,
    )
    // Reflejo de luz que cruza el logotipo cada pocos segundos.
    val shine by rememberInfiniteTransition(label = "brillo").animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 5000
                -0.3f at 0 using FastOutSlowInEasing
                1.3f at 1400
                1.3f at 5000
            }
        ),
        label = "brillo",
    )
    val shineModifier = Modifier
        // Capa propia: el reflejo solo se pinta sobre el logotipo, no sobre el fondo.
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val band = size.height * 0.9f
            val x = size.width * shine
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = if (dark) 0.55f else 0.75f), Color.Transparent),
                    start = Offset(x - band, 0f),
                    end = Offset(x + band * 0.4f, size.height),
                ),
                blendMode = BlendMode.SrcAtop,
            )
        }
    Row(modifier.then(shineModifier), verticalAlignment = Alignment.CenterVertically) {
        if (showIcon && icon != null) {
            Image(
                icon, null,
                Modifier
                    .size((fontSize.value * 1.15f).dp)
                    .shadow(6.dp, RoundedCornerShape(9.dp), ambientColor = BrandColors.blue, spotColor = BrandColors.blue)
                    .clip(RoundedCornerShape(9.dp)),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(brush = feedBrush)) { append("Feed") }
                withStyle(SpanStyle(brush = vibeBrush)) { append("Vibe") }
                withStyle(SpanStyle(color = BrandColors.yellow, fontSize = fontSize * 0.6f, baselineShift = BaselineShift(0.9f))) {
                    append("✦")
                }
            },
            style = TextStyle(
                fontSize = fontSize,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.02).em,
                shadow = glow,
            ),
            maxLines = 1,
        )
    }
}
