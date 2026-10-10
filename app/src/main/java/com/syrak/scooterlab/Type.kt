package com.syrak.scooterlab.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/* ============================================================================
 *  Syrak Type System
 *  - UI copy      : system sans, tight tracking, high contrast.
 *  - Data readouts: monospace so digits never jitter during live telemetry.
 *  NOTE: swap FontFamily.Monospace for a bundled JetBrains Mono / Roboto Mono
 *  (res/font) in production for a more bespoke industrial feel.
 * ========================================================================== */

private val Mono = FontFamily.Monospace
private val Sans = FontFamily.SansSerif

/** Material3 baseline typography, retuned for the industrial aesthetic. */
val SyrakMaterialTypography = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 19.sp, letterSpacing = 0.1.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
)

/**
 * Domain-specific text styles for gauges, telemetry values, units and labels.
 * Exposed via [LocalSyrakType].
 */
@Immutable
data class SyrakType(
    /** Oversized hero numeral inside the primary speed gauge. */
    val gaugeNumber: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-2).sp),
    val dataXl: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
    val dataLg: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
    val dataMd: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    val dataSm: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
    /** Uppercase unit suffixes (KM/H, V, A). */
    val unit: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.5.sp),
    /** All-caps section headers with wide tracking. */
    val sectionLabel: TextStyle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 2.2.sp),
    val microLabel: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 1.4.sp),
)

val LocalSyrakType = staticCompositionLocalOf { SyrakType() }
