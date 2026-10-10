package com.syrak.scooterlab.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Strict 4pt spatial grid. Every dimension in the app derives from this scale so
 * layouts stay on-rhythm and pixel-aligned across densities.
 */
@Immutable
data class SyrakSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    val huge: Dp = 48.dp,
    val gutter: Dp = 20.dp,
    val hairline: Dp = 1.dp,
)

val LocalSyrakSpacing = staticCompositionLocalOf { SyrakSpacing() }
