package com.syrak.scooterlab.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val SyrakColorScheme = darkColorScheme(
    primary = Crimson,
    onPrimary = Color.White,
    primaryContainer = CrimsonDim,
    onPrimaryContainer = TextPrimary,
    secondary = Acid,
    onSecondary = SyrakBlackTrue,
    secondaryContainer = AcidDim,
    onSecondaryContainer = SyrakBlackTrue,
    tertiary = Cyan,
    onTertiary = SyrakBlackTrue,
    background = SyrakBlack,
    onBackground = TextPrimary,
    surface = SyrakSlate,
    onSurface = TextPrimary,
    surfaceVariant = SyrakSlateElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SyrakSlate,
    surfaceContainerHigh = SyrakSlateElevated,
    surfaceContainerHighest = SyrakSlateHigh,
    outline = SyrakOutline,
    outlineVariant = SyrakOutlineSoft,
    error = Crimson,
    onError = Color.White,
    scrim = Color(0xCC000000),
)

/**
 * The single theming entry point. Always dark — this product has no light mode by
 * design; a scooter diagnostic tool lives in the dark.
 */
@Composable
fun SyrakTheme(
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    CompositionLocalProvider(
        LocalSyrakExtendedColors provides SyrakExtendedColors(),
        LocalSyrakSpacing provides SyrakSpacing(),
        LocalSyrakType provides SyrakType(),
    ) {
        MaterialTheme(
            colorScheme = SyrakColorScheme,
            typography = SyrakMaterialTypography,
            shapes = SyrakShapes,
            content = content,
        )
    }
}

/** Ergonomic accessors mirroring the [MaterialTheme] object pattern. */
object SyrakTheme {
    val colors: SyrakExtendedColors
        @Composable @ReadOnlyComposable get() = LocalSyrakExtendedColors.current
    val spacing: SyrakSpacing
        @Composable @ReadOnlyComposable get() = LocalSyrakSpacing.current
    val type: SyrakType
        @Composable @ReadOnlyComposable get() = LocalSyrakType.current
}
