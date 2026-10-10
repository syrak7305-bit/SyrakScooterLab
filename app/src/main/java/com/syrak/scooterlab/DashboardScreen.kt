package com.syrak.scooterlab.ui.dashboard

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
import com.syrak.scooterlab.R
import com.syrak.scooterlab.core.ble.ConnectionState
import com.syrak.scooterlab.core.ble.DiscoveredScooter
import com.syrak.scooterlab.core.ble.ScooterBrand
import com.syrak.scooterlab.feature.telemetry.BatteryHealth
import com.syrak.scooterlab.ui.components.GaugeRing
import com.syrak.scooterlab.ui.components.HairlineDivider
import com.syrak.scooterlab.ui.components.NeonCard
import com.syrak.scooterlab.ui.components.SectionHeader
import com.syrak.scooterlab.ui.components.StatTile
import com.syrak.scooterlab.ui.components.StatusChip
import com.syrak.scooterlab.ui.components.SyrakButton
import com.syrak.scooterlab.ui.components.SyrakButtonVariant
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
            .padding(horizontal = spacing.gutter, vertical = spacing.lg),
    ) {
        TopBar(state)
        Spacer(Modifier.height(spacing.lg))

        state.banner?.let { banner ->
            BannerStrip(banner, state.killSwitchEngaged)
            Spacer(Modifier.height(spacing.md))
        }

        ConnectionPanel(state, onScanToggle, onConnect, onDisconnect)
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

        SectionHeader("Safety", accent = SyrakTheme.colors.danger)
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

/* --------------------------------- Top bar --------------------------------- */

@Composable
private fun TopBar(state: DashboardUiState) {
    val spacing = SyrakTheme.spacing
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(SyrakTheme.colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_bolt),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(spacing.md))
        Column {
            Text(
                text = "SYRAK",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
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
            StatusChip(text = "Sim", color = SyrakTheme.colors.warning)
            Spacer(Modifier.width(spacing.sm))
        }
        val (label, color) = state.connection.statusMeta()
        StatusChip(text = label, color = color)
    }
}

private fun ConnectionState.statusMeta(): Pair<String, Color> = when (this) {
    is ConnectionState.Ready -> "Online" to com.syrak.scooterlab.ui.theme.Acid
    is ConnectionState.Scanning -> "Scanning" to com.syrak.scooterlab.ui.theme.Cyan
    is ConnectionState.Connecting, is ConnectionState.Discovering, is ConnectionState.Authenticating ->
        "Linking" to com.syrak.scooterlab.ui.theme.Amber
    is ConnectionState.Failed -> "Fault" to com.syrak.scooterlab.ui.theme.Crimson
    ConnectionState.Disconnected -> "Offline" to com.syrak.scooterlab.ui.theme.TextSecondary
    ConnectionState.Idle -> "Idle" to com.syrak.scooterlab.ui.theme.TextSecondary
}

@Composable
private fun BannerStrip(text: String, danger: Boolean) {
    val color = if (danger) SyrakTheme.colors.danger else SyrakTheme.colors.data
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = SyrakTheme.spacing.md, vertical = SyrakTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(SyrakTheme.spacing.sm))
        Text(text = text, style = SyrakTheme.type.dataSm, color = color)
    }
}

/* ------------------------------ Connection panel --------------------------- */

@Composable
private fun ConnectionPanel(
    state: DashboardUiState,
    onScanToggle: () -> Unit,
    onConnect: (DiscoveredScooter) -> Unit,
    onDisconnect: () -> Unit,
) {
    val spacing = SyrakTheme.spacing
    val ready = state.connection as? ConnectionState.Ready

    NeonCard(modifier = Modifier.fillMaxWidth(), accent = if (ready != null) SyrakTheme.colors.success else null) {
        if (ready != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_scooter),
                    contentDescription = null,
                    tint = SyrakTheme.colors.success,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = ready.name ?: "Unknown controller",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${ready.brand.displayName} · ${ready.address} · MTU ${ready.mtu}",
                        style = SyrakTheme.type.dataSm,
                        color = SyrakTheme.colors.onSurfaceMuted,
                    )
                }
            }
            Spacer(Modifier.height(spacing.lg))
            SyrakButton(
                text = "Disconnect",
                onClick = onDisconnect,
                variant = SyrakButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_bluetooth),
                    contentDescription = null,
                    tint = SyrakTheme.colors.data,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "No controller linked",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Scan the airspace for Ninebot, Xiaomi or Navee units.",
                        style = SyrakTheme.type.dataSm,
                        color = SyrakTheme.colors.onSurfaceMuted,
                    )
                }
            }
            Spacer(Modifier.height(spacing.lg))
            SyrakButton(
                text = if (state.connection is ConnectionState.Scanning) "Stop scan" else "Scan airspace",
                onClick = onScanToggle,
                leadingIcon = painterResource(R.drawable.ic_bluetooth),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.scanResults.isNotEmpty()) {
                Spacer(Modifier.height(spacing.md))
                HairlineDivider(Modifier.fillMaxWidth())
                Spacer(Modifier.height(spacing.sm))
                state.scanResults.take(6).forEach { scooter ->
                    ScanResultRow(scooter, onConnect)
                }
            }
        }
    }
}

@Composable
private fun ScanResultRow(scooter: DiscoveredScooter, onConnect: (DiscoveredScooter) -> Unit) {
    val spacing = SyrakTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onConnect(scooter) }
            .padding(vertical = spacing.md, horizontal = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = scooter.name ?: "Unnamed device",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = scooter.address,
                style = SyrakTheme.type.microLabel,
                color = SyrakTheme.colors.onSurfaceFaint,
            )
        }
        Text(
            text = "${scooter.rssi} dBm",
            style = SyrakTheme.type.dataSm,
            color = SyrakTheme.colors.data,
        )
        Spacer(Modifier.width(spacing.md))
        StatusChip(text = scooter.brand.displayName, color = SyrakTheme.colors.accent)
    }
}

/* --------------------------------- Speed ----------------------------------- */

@Composable
private fun SpeedPanel(state: DashboardUiState) {
    val spacing = SyrakTheme.spacing
    val telemetry = state.telemetry
    NeonCard(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            GaugeRing(
                value = telemetry.speedKmh,
                maxValue = 45f,
                diameter = 210.dp,
                unit = "KM/H",
                caption = if (state.isSimulated) "simulated feed" else "live feed",
            )
        }
        Spacer(Modifier.height(spacing.md))
        HairlineDivider(Modifier.fillMaxWidth())
        Spacer(Modifier.height(spacing.md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MiniMetric("Power", "%.0f".format(telemetry.powerW), "W")
            MiniMetric("Odometer", "%.1f".format(telemetry.odometerKm), "KM")
            MiniMetric("Range", "%.1f".format(telemetry.estimatedRangeKm), "KM")
        }
    }
}

@Composable
private fun MiniMetric(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), style = SyrakTheme.type.microLabel, color = SyrakTheme.colors.onSurfaceMuted)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = SyrakTheme.type.dataMd, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(3.dp))
            Text(unit, style = SyrakTheme.type.unit, color = SyrakTheme.colors.data, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/* --------------------------------- Power ----------------------------------- */

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
                value = t.batteryPercent.toString(),
                unit = "%",
                icon = painterResource(R.drawable.ic_battery),
                accent = batteryAccent(t.batteryPercent),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Motor temp",
                value = "%.0f".format(t.motorTempC),
                unit = "°C",
                icon = painterResource(R.drawable.ic_thermometer),
                accent = tempAccent(t.motorTempC),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/* -------------------------------- Battery ---------------------------------- */

@Composable
private fun BatteryPanel(state: DashboardUiState) {
    val spacing = SyrakTheme.spacing
    val t = state.telemetry
    NeonCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Pack health",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            val (label, color) = when (t.health) {
                BatteryHealth.NOMINAL -> "Nominal" to SyrakTheme.colors.success
                BatteryHealth.DEGRADED -> "Degraded" to SyrakTheme.colors.warning
                BatteryHealth.CRITICAL -> "Critical" to SyrakTheme.colors.danger
            }
            StatusChip(text = label, color = color)
        }
        Spacer(Modifier.height(spacing.md))
        HairlineDivider(Modifier.fillMaxWidth())
        Spacer(Modifier.height(spacing.md))
        DataRow("Cell min", "%.3f V".format(t.cellMinV))
        DataRow("Cell max", "%.3f V".format(t.cellMaxV))
        DataRow("Cell delta", "%.0f mV".format(t.cellDeltaMv))
        DataRow("Battery temp", "%.1f °C".format(t.batteryTempC))
    }
}

@Composable
private fun DataRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = SyrakTheme.colors.onSurfaceMuted, modifier = Modifier.weight(1f))
        Text(value, style = SyrakTheme.type.dataSm, color = MaterialTheme.colorScheme.onSurface)
    }
}

/* --------------------------------- Safety ---------------------------------- */

@Composable
private fun SafetyPanel(state: DashboardUiState, onToggleKillSwitch: () -> Unit) {
    val spacing = SyrakTheme.spacing
    val engaged = state.killSwitchEngaged
    NeonCard(
        modifier = Modifier.fillMaxWidth(),
        accent = SyrakTheme.colors.danger,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_shield),
                contentDescription = null,
                tint = SyrakTheme.colors.danger,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Emergency killswitch",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Cuts throttle output and locks the controller. Electronic containment only — always use the physical brake.",
                    style = SyrakTheme.type.dataSm,
                    color = SyrakTheme.colors.onSurfaceMuted,
                )
            }
        }
        Spacer(Modifier.height(spacing.lg))
        SyrakButton(
            text = if (engaged) "Release containment" else "Engage killswitch",
            onClick = onToggleKillSwitch,
            variant = if (engaged) SyrakButtonVariant.Secondary else SyrakButtonVariant.Danger,
            leadingIcon = painterResource(R.drawable.ic_shield),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/* --------------------------------- Tuning ---------------------------------- */

@Composable
private fun TuningPanel(state: DashboardUiState, onApplyManeuver: (Int) -> Unit) {
    val spacing = SyrakTheme.spacing
    var target by remember { mutableIntStateOf(30) }
    val options = listOf(25, 30, 35, 40)

    NeonCard(modifier = Modifier.fillMaxWidth(), accent = SyrakTheme.colors.accent) {
        Text(
            text = "German Maneuver",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Volatile RAM patch — raises the speed ceiling until the next power cycle. Baseline values are captured for one-tap revert.",
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
private fun SpeedOption(value: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val accent = SyrakTheme.colors.accent
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(vertical = SyrakTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value.toString(),
                style = SyrakTheme.type.dataMd,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "KM/H",
                style = SyrakTheme.type.unit,
                color = if (selected) accent else SyrakTheme.colors.onSurfaceFaint,
            )
        }
    }
}

/* -------------------------------- Console ---------------------------------- */

@Composable
private fun ConsolePanel(state: DashboardUiState) {
    val spacing = SyrakTheme.spacing
    NeonCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(spacing.md)) {
        val lines = state.log.takeLast(6)
        if (lines.isEmpty()) {
            Text("Awaiting telemetry…", style = SyrakTheme.type.dataSm, color = SyrakTheme.colors.onSurfaceFaint)
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

/* --------------------------------- Helpers --------------------------------- */

private fun batteryAccent(percent: Int): Color = when {
    percent <= 15 -> com.syrak.scooterlab.ui.theme.Crimson
    percent <= 35 -> com.syrak.scooterlab.ui.theme.Amber
    else -> com.syrak.scooterlab.ui.theme.Acid
}

private fun tempAccent(tempC: Float): Color = when {
    tempC >= 70f -> com.syrak.scooterlab.ui.theme.Crimson
    tempC >= 55f -> com.syrak.scooterlab.ui.theme.Amber
    else -> com.syrak.scooterlab.ui.theme.Cyan
}
