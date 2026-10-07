package com.feedvibe.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
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

/** «FeedVibe» con el icono y los degradados del logotipo. */
@Composable
fun BrandTitle(modifier: Modifier = Modifier, fontSize: TextUnit = 26.sp, showIcon: Boolean = true) {
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
