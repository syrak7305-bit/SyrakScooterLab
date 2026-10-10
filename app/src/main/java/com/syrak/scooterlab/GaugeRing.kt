package com.syrak.scooterlab.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syrak.scooterlab.ui.theme.SyrakTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Precision instrument gauge: a 270° arc with graduated ticks, an accent
 * progress sweep and a hero monospace readout. Drawn entirely on a Canvas so it
 * scales crisply at any density.
 */
@Composable
fun GaugeRing(
    value: Float,
    maxValue: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 220.dp,
    unit: String = "KM/H",
    accent: Color? = null,
    caption: String? = null,
) {
    val accentColor = accent ?: SyrakTheme.colors.accent
    val track = SyrakTheme.colors.hairline
    val type = SyrakTheme.type
    val startAngle = 135f
    val sweepTotal = 270f
    val fraction = (value / maxValue).coerceIn(0f, 1f)

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeW = 14.dp.toPx()
            val inset = strokeW / 2f + 8.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)

            val cx = size.width / 2f
            val cy = size.height / 2f
            val rOuter = size.width / 2f - 2.dp.toPx()
            val rInner = rOuter - 9.dp.toPx()

            // Graduated tick ring — major every 5th tick.
            val tickCount = 45
            for (i in 0..tickCount) {
                val angle = Math.toRadians((startAngle + sweepTotal * i / tickCount).toDouble())
                val c = cos(angle).toFloat()
                val s = sin(angle).toFloat()
                val major = i % 5 == 0
                val rStart = if (major) rInner - 4.dp.toPx() else rInner
                drawLine(
                    color = if (major) track else track.copy(alpha = 0.5f),
                    start = Offset(cx + c * rStart, cy + s * rStart),
                    end = Offset(cx + c * rOuter, cy + s * rOuter),
                    strokeWidth = if (major) 2f else 1f,
                )
            }

            // Track + progress sweep.
            drawArc(
                color = track,
                startAngle = startAngle,
                sweepAngle = sweepTotal,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeW, cap = StrokeCap.Round),
            )
            if (fraction > 0f) {
                drawArc(
                    color = accentColor,
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value.toInt().toString(),
                style = type.gaugeNumber,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = unit,
                style = type.unit,
                color = SyrakTheme.colors.onSurfaceMuted,
            )
            if (caption != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = caption.uppercase(),
                    style = type.microLabel,
                    color = accentColor,
                )
            }
        }
    }
}
