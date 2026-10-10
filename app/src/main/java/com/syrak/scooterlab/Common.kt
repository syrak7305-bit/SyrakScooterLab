package com.syrak.scooterlab.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syrak.scooterlab.ui.theme.SyrakTheme

/* ============================================================================
 *  Core surfaces & primitives — every screen is assembled from these.
 * ========================================================================== */

/**
 * The canonical panel: dark slate fill, hairline outline, optional accent edge.
 * A faint accent top-rule is drawn when [accent] is supplied, giving cards a
 * subtle "armed" state without resorting to heavy shadows.
 */
@Composable
fun NeonCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    contentPadding: PaddingValues? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = SyrakTheme.spacing
    val shape = MaterialTheme.shapes.medium
    val padding = contentPadding ?: PaddingValues(spacing.lg)
    val borderColor = accent?.copy(alpha = 0.45f) ?: SyrakTheme.colors.hairline

    Column(
        modifier = modifier
            .clip(shape)
            .background(SyrakTheme.colors.surfaceElevated, shape)
            .border(BorderStroke(spacing.hairline, borderColor), shape)
            .padding(padding),
        content = content,
    )
}

/** All-caps section label with an accent tick, matching the industrial language. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    val spacing = SyrakTheme.spacing
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 13.dp)
                .background(accent ?: SyrakTheme.colors.accent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(spacing.sm))
        Text(
            text = title.uppercase(),
            style = SyrakTheme.type.sectionLabel,
            color = SyrakTheme.colors.onSurfaceMuted,
        )
    }
}

/** Status pill: coloured dot + tracked label. */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val shape = CircleShape
    Row(
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.10f), shape)
            .border(BorderStroke(1.dp, color.copy(alpha = 0.40f)), shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text.uppercase(),
            style = SyrakTheme.type.microLabel,
            color = color,
        )
    }
}

@Composable
fun HairlineDivider(
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    Box(
        modifier
            .height(SyrakTheme.spacing.hairline)
            .background(color ?: SyrakTheme.colors.hairlineSoft),
    )
}

enum class SyrakButtonVariant { Primary, Secondary, Danger, Ghost }

/** Flat, sharp, high-contrast button. No elevation, no rounded blobs. */
@Composable
fun SyrakButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: SyrakButtonVariant = SyrakButtonVariant.Primary,
    enabled: Boolean = true,
    leadingIcon: Painter? = null,
) {
    val spacing = SyrakTheme.spacing
    val shape = RoundedCornerShape(12.dp)
    val accent = SyrakTheme.colors.accent
    val danger = SyrakTheme.colors.danger

    val (bg, fg, border) = when (variant) {
        SyrakButtonVariant.Primary -> Triple(accent, Color.White, accent)
        SyrakButtonVariant.Danger -> Triple(danger, Color.White, danger)
        SyrakButtonVariant.Secondary -> Triple(Color.Transparent, MaterialTheme.colorScheme.onSurface, SyrakTheme.colors.hairline)
        SyrakButtonVariant.Ghost -> Triple(Color.Transparent, SyrakTheme.colors.onSurfaceMuted, Color.Transparent)
    }

    val alpha = if (enabled) 1f else 0.4f

    Box(
        modifier = modifier
            .clip(shape)
            .background(bg.copy(alpha = if (variant == SyrakButtonVariant.Primary || variant == SyrakButtonVariant.Danger) bg.alpha * alpha else bg.alpha), shape)
            .border(BorderStroke(1.dp, border.copy(alpha = alpha)), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = spacing.xl, vertical = spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, tint = fg.copy(alpha = alpha), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(spacing.sm))
            }
            Text(
                text = text.uppercase(),
                style = SyrakTheme.type.sectionLabel,
                color = fg.copy(alpha = alpha),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Compact metric tile: icon + label over a monospace value + unit. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    icon: Painter? = null,
    accent: Color? = null,
) {
    val spacing = SyrakTheme.spacing
    val accentColor = accent ?: SyrakTheme.colors.data
    NeonCard(modifier = modifier, contentPadding = PaddingValues(spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label.uppercase(),
                style = SyrakTheme.type.microLabel,
                color = SyrakTheme.colors.onSurfaceMuted,
            )
        }
        Spacer(Modifier.height(spacing.sm))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = SyrakTheme.type.dataLg,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = unit,
                    style = SyrakTheme.type.unit,
                    color = accentColor,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}
