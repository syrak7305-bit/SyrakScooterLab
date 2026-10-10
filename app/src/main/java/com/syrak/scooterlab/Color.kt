package com.syrak.scooterlab.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/* ============================================================================
 *  Syrak Industrial Palette — single source of truth for colour.
 *  Matte black base, dark slate surfaces, neon crimson + acid green accents.
 * ========================================================================== */

val SyrakBlack = Color(0xFF0C0C12)
val SyrakBlackTrue = Color(0xFF08080C)
val SyrakSlate = Color(0xFF161A22)
val SyrakSlateElevated = Color(0xFF1E232E)
val SyrakSlateHigh = Color(0xFF242B38)
val SyrakOutline = Color(0xFF2A3140)
val SyrakOutlineSoft = Color(0xFF20252F)

val Crimson = Color(0xFFFF3C3C)
val CrimsonDim = Color(0xFFB32626)
val CrimsonGlow = Color(0x33FF3C3C)
val Acid = Color(0xFF00E676)
val AcidDim = Color(0xFF00A855)
val AcidGlow = Color(0x3300E676)
val Cyan = Color(0xFF00E5FF)
val Amber = Color(0xFFFFB020)

val TextPrimary = Color(0xFFF2F4F8)
val TextSecondary = Color(0xFF8A93A6)
val TextTertiary = Color(0xFF5A6376)

/**
 * Extended semantic tokens that Material3's [androidx.compose.material3.ColorScheme]
 * does not model. Exposed through [LocalSyrakExtendedColors].
 */
@Immutable
data class SyrakExtendedColors(
    val success: Color = Acid,
    val successDim: Color = AcidDim,
    val warning: Color = Amber,
    val danger: Color = Crimson,
    val dangerDim: Color = CrimsonDim,
    val data: Color = Cyan,
    val accent: Color = Crimson,
    val hairline: Color = SyrakOutline,
    val hairlineSoft: Color = SyrakOutlineSoft,
    val surfaceElevated: Color = SyrakSlateElevated,
    val surfaceHigh: Color = SyrakSlateHigh,
    val glowSuccess: Color = AcidGlow,
    val glowDanger: Color = CrimsonGlow,
    val onSurfaceMuted: Color = TextSecondary,
    val onSurfaceFaint: Color = TextTertiary,
)

val LocalSyrakExtendedColors = staticCompositionLocalOf { SyrakExtendedColors() }
