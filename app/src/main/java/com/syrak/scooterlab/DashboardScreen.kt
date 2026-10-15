package com.syrak.scooterlab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syrak.scooterlab.core.ble.ConnectionState
import com.syrak.scooterlab.core.ble.DiscoveredScooter
import com.syrak.scooterlab.feature.telemetry.BatteryHealth
import com.syrak.scooterlab.ui.components.GaugeRing
import com.syrak.scooterlab.ui.components.HairlineDivider
import com.syrak.scooterlab.ui.components.NeonCard
import com.syrak.scooterlab.ui.components.SectionHeader
import com.syrak.scooterlab.ui.components.StatTile
import com.syrak.scooterlab.ui.components.StatusChip
import com.syrak.scooterlab.ui.components.SyrakButton
import com.syrak.scooterlab.ui.components.SyrakButtonVariant
import com.syrak.scooterlab.ui.dashboard.DashboardUiState
import com.syrak.scooterlab.ui.dashboard.DashboardViewModel
import com.syrak.scooterlab.ui.theme.SyrakBlack
import com.syrak.scooterlab.ui.theme.SyrakTheme

@Composable
fun DashboardRoute(viewModel: DashboardViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DashboardContent(
        state = state,
        onScanToggle = viewModel::onScanToggle,
        onConnect = viewModel::onConnect,
        onDisconnect = viewModel::onDisconnect,
        onApplyManeuver = viewModel::onApplyManeuver,
        onToggleKillSwitch = viewModel::onToggleKillSwitch,
    )
}

@Composable
fun DashboardContent(
    state: DashboardUiState,
    onScanToggle: () -> Unit,
    onConnect: (DiscoveredScooter) -> Unit,
    onDisconnect: () -> Unit,
    onApplyManeuver: (Int) -> Unit,
    onToggleKillSwitch: () -> Unit,
) {
    val spacing = SyrakTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SyrakBlack)
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = spacing.gutter,
                vertical = spacing.lg,
            ),
    ) {
        TopBar(state)
        Spacer(Modifier.height(spacing.lg))

        state.banner?.let { banner ->
            BannerStrip(banner, state.killSwitchEngaged)
            Spacer(Modifier.height(spacing.md))
        }

        ConnectionPanel(
            state,
            onScanToggle,
            onConnect,
            onDisconnect,
        )
        Spacer(Modifier.height(spacing.xl))

        SpeedPanel(state)
        Spacer(Modifier.height(spacing.xl))

        SectionHeader("Power")
        Spacer(Modifier.height(spacing.md))
        PowerGrid(state)
        Spacer(Modifier.height(spacing.xl))

        SectionHeader("Thermal & Cells")
        Spacer(Modifier.height(spacing.md))
        BatteryPanel(state)
        Spacer(Modifier.height(spacing.xl))

        SectionHeader(
            "Safety",
            accent = SyrakTheme.colors.danger,
        )
        Spacer(Modifier.height(spacing.md))
        SafetyPanel(state, onToggleKillSwitch)
        Spacer(Modifier.height(spacing.xl))

        SectionHeader("Tuning")
        Spacer(Modifier.height(spacing.md))
        TuningPanel(state, onApplyManeuver)
        Spacer(Modifier.height(spacing.xl))

        SectionHeader("Console")
        Spacer(Modifier.height(spacing.md))
        ConsolePanel(state)
        Spacer(Modifier.height(spacing.huge))
    }
}

/* Top bar */

@Composable
private fun TopBar(state: DashboardUiState) {
    val spacing = SyrakTheme.spacing

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(SyrakTheme.colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_bolt),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }

        Spacer(Modifier.width(spacing.md))

        Column {
            Text(
                text = "SYRAK",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Black,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = "SCOOTERLAB",
                style = SyrakTheme.type.microLabel,
                color = SyrakTheme.colors.onSurfaceMuted,
            )
        }

        Spacer(Modifier.weight(1f))

        if (state.isSimulated) {
            StatusChip(
                text = "Sim",
                color = SyrakTheme.colors.warning,
            )
            Spacer(Modifier.width(spacing.sm))
        }

        val (label, color) = state.connection.statusMeta()
        StatusChip(text = label, color = color)
    }
}

private fun ConnectionState.statusMeta(): Pair<String, Color> =
    when (this) {
        is ConnectionState.Ready ->
            "Online" to com.syrak.scooterlab.ui.theme.Acid

        is ConnectionState.Scanning ->
            "Scanning" to com.syrak.scooterlab.ui.theme.Cyan

        is ConnectionState.Connecting ->
            "Connecting" to com.syrak.scooterlab.ui.theme.Amber

        is ConnectionState.Discovering ->
            "Bringing link up" to com.syrak.scooterlab.ui.theme.Amber

        is ConnectionState.Failed ->
            "Link failed" to com.syrak.scooterlab.ui.theme.Crimson

        else -> "Idle" to com.syrak.scooterlab.ui.theme.Slate
    }

/* Speed */

@Composable
private fun SpeedPanel(state: DashboardUiState) {
    val spacing = SyrakTheme.spacing
    val t = state.telemetry

    NeonCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${t.speedKmh} km/h",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Text(
                    text = "${t.odometerKm} km",
                    style = SyrakTheme.type.dataSm,
                    color = SyrakTheme.colors.onSurfaceMuted,
                )
            }

            GaugeRing(percent = (t.batteryPercent ?: 0) / 100f)
        }

        Spacer(Modifier.height(spacing.lg))

        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            SyrakButton(
                text = "Panic",
                onClick = { /* TODO */ },
                variant = SyrakButtonVariant.Destructive,
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(spacing.sm))

            SyrakButton(
                text = "Eco",
                onClick = { /* TODO */ },
                variant = SyrakButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/* Power */

@Composable
private fun PowerGrid(state: DashboardUiState) {
    val spacing = SyrakTheme.spacing
    val t = state.telemetry

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            StatTile(
                label = "Voltage",
                value = "%.1f".format(t.voltageV),
                unit = "V",
                icon = painterResource(R.drawable.ic_bolt),
                modifier = Modifier.weight(1f),
            )

            StatTile(
                label = "Current",
                value = "%.1f".format(t.currentA),
                unit = "A",
                icon = painterResource(R.drawable.ic_signal),
                modifier = Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            StatTile(
                label = "Battery",
                value = t.batteryPercent?.toString() ?: "--",
                unit = "%",
                icon = painterResource(R.drawable.ic_battery),
                accent = batteryAccent(t.batteryPercent),
                modifier = Modifier.weight(1f),
            )

            StatTile(
                label = "Motor temp",
                value = t.motorTempC?.let { "%.0f".format(it) } ?: "--",
                unit = "°C",
                icon = painterResource(R.drawable.ic_thermometer),
                accent = tempAccent(t.motorTempC),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/* Battery */

@Composable
private fun BatteryPanel(state: DashboardUiState) {
    val spacing = SyrakTheme.spacing
    val t = state.telemetry

    NeonCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Pack health",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )

            val (label, color) = when (t.health) {
                BatteryHealth.NOMINAL ->
                    "Nominal" to SyrakTheme.colors.success

                BatteryHealth.DEGRADED ->
                    "Degraded" to SyrakTheme.colors.warning

                BatteryHealth.CRITICAL ->
                    "Critical" to SyrakTheme.colors.danger

                else ->
                    "Unknown" to SyrakTheme.colors.onSurfaceMuted
            }

            StatusChip(text = label, color = color)
        }

        Spacer(Modifier.height(spacing.md))
        HairlineDivider(Modifier.fillMaxWidth())
        Spacer(Modifier.height(spacing.md))

        DataRow("Cell min", t.cellMinV?.let { "%.3f V".format(it) } ?: "--")
        DataRow("Cell max", t.cellMaxV?.let { "%.3f V".format(it) } ?: "--")
        DataRow("Delta", if (t.cellMinV != null && t.cellMaxV != null) "%.0f mV".format((t.cellMaxV - t.cellMinV) * 1000) else "--")

        Spacer(Modifier.height(spacing.md))

        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            DataRow("SoH", t.fullChargeCapacityMah?.let { design ->
                val designCap = t.designCapacityMah
                if (designCap != null && designCap > 0) {
                    val soh = design.toDouble() / designCap.toDouble() * 100.0
                    String.format(Locale.US, "%.1f %%", soh)
                } else "--"
            } ?: "--")

            DataRow("Cycles", t.cycleCount?.toString() ?: "--")
        }
    }
}

/* Safety */

@Composable
private fun SafetyPanel(state: DashboardUiState, onToggleKillSwitch: () -> Unit) {
    val spacing = SyrakTheme.spacing

    NeonCard(modifier = Modifier.fillMaxWidth()) {
        Column {        
            SyrakButton(
                text = if (state.killSwitchEngaged) "Release Kill Switch" else "Engage Kill Switch",
                onClick = onToggleKillSwitch,
                variant = if (state.killSwitchEngaged) SyrakButtonVariant.Secondary else SyrakButtonVariant.Destructive,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/* Tuning */

@Composable
private fun TuningPanel(state: DashboardUiState, onApplyManeuver: (Int) -> Unit) {
    val spacing = SyrakTheme.spacing
    var target by remember { mutableIntStateOf(25) }
    val options = listOf(10, 15, 20, 25, 30)

    NeonCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Apply a conservative speed limit to the controller",
            style = SyrakTheme.type.dataSm,
            color = SyrakTheme.colors.onSurfaceMuted,
        )

        Spacer(Modifier.height(spacing.lg))

        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            options.forEach { option ->
                SpeedOption(
                    value = option,
                    selected = option == target,
                    onClick = { target = option },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(spacing.lg))

        SyrakButton(
            text = if (state.busy) "Working…" else "Apply patch",
            onClick = { onApplyManeuver(target) },
            enabled = !state.busy,
            leadingIcon = painterResource(R.drawable.ic_tune),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SpeedOption(
    value: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val accent = SyrakTheme.colors.accent

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) accent.copy(alpha = 0.14f)
                else Color.Transparent,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(vertical = SyrakTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value.toString(),
                style = SyrakTheme.type.dataMd,
                color = if (selected) {
                    accent
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            Text(
                text = "KM/H",
                style = SyrakTheme.type.unit,
                color = if (selected) {
                    accent
                } else {
                    SyrakTheme.colors.onSurfaceFaint
                },
            )
        }
    }
}

/* Console */

@Composable
private fun ConsolePanel(state: DashboardUiState) {
    val spacing = SyrakTheme.spacing

    NeonCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(spacing.md),
    ) {
        val lines = state.log.takeLast(6)

        if (lines.isEmpty()) {
            Text(
                text = "Awaiting telemetry…",
                style = SyrakTheme.type.dataSm,
                color = SyrakTheme.colors.onSurfaceFaint,
            )
        } else {
            lines.forEach { line ->
                Text(
                    text = "> $line",
                    style = SyrakTheme.type.dataSm,
                    color = SyrakTheme.colors.data,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

/* Helpers */

private fun batteryAccent(percent: Int?): Color = when {
    percent == null -> SyrakTheme.colors.onSurfaceFaint
    percent <= 15 ->
        com.syrak.scooterlab.ui.theme.Crimson

    percent <= 35 ->
        com.syrak.scooterlab.ui.theme.Amber

    else ->
        com.syrak.scooterlab.ui.theme.Acid
}

private fun tempAccent(tempC: Float?): Color = when {
    tempC == null -> SyrakTheme.colors.onSurfaceFaint
    tempC >= 70f ->
        com.syrak.scooterlab.ui.theme.Crimson

    tempC >= 55f ->
        com.syrak.scooterlab.ui.theme.Amber

    else ->
        com.syrak.scooterlab.ui.theme.Cyan
}
