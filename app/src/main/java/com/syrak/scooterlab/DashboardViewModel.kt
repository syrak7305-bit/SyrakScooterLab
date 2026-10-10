package com.syrak.scooterlab.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.syrak.scooterlab.core.ble.ConnectionState
import com.syrak.scooterlab.core.ble.DiscoveredScooter
import com.syrak.scooterlab.core.ble.GattEvent
import com.syrak.scooterlab.data.ScooterRepository
import com.syrak.scooterlab.feature.telemetry.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immutable snapshot the dashboard renders from. */
data class DashboardUiState(
    val connection: ConnectionState = ConnectionState.Idle,
    val telemetry: Telemetry = Telemetry.Empty,
    val scanResults: List<DiscoveredScooter> = emptyList(),
    val isSimulated: Boolean = true,
    val killSwitchEngaged: Boolean = false,
    val busy: Boolean = false,
    val banner: String? = null,
    val log: List<String> = emptyList(),
)

class DashboardViewModel(
    private val repository: ScooterRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        observeStreams()
        // Seed the dashboard with synthetic data so the instrument panel is live
        // before any hardware is attached. Replaced the moment a device connects.
        repository.startSimulation()
    }

    private fun observeStreams() {
        viewModelScope.launch {
            repository.connectionState.collect { c -> _state.update { it.copy(connection = c) } }
        }
        viewModelScope.launch {
            repository.telemetry.collect { t -> _state.update { it.copy(telemetry = t) } }
        }
        viewModelScope.launch {
            repository.scanResults.collect { r -> _state.update { it.copy(scanResults = r) } }
        }
        viewModelScope.launch {
            repository.gattEvents.collect { ev -> pushLog(ev.toLogLine()) }
        }
    }

    /* ------------------------------- Actions --------------------------------- */

    fun onScanToggle() {
        val scanning = _state.value.connection is ConnectionState.Scanning
        if (scanning) repository.stopScan() else repository.startScan(filtered = true)
    }

    fun onConnect(scooter: DiscoveredScooter) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, banner = "Linking to ${scooter.name ?: scooter.address}…") }
            val result = repository.connect(scooter)
            _state.update {
                it.copy(
                    busy = false,
                    isSimulated = !result.isSuccess,
                    banner = if (result.isSuccess) {
                        "Controller online"
                    } else {
                        "Link failed: ${result.exceptionOrNull()?.message ?: "unknown"}"
                    },
                )
            }
        }
    }

    fun onDisconnect() {
        repository.disconnect()
        _state.update { it.copy(isSimulated = true, killSwitchEngaged = false, banner = "Session closed") }
    }

    fun onApplyManeuver(targetSpeedKmh: Int) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, banner = "Applying German Maneuver…") }
            val result = repository.applyGermanManeuver(targetSpeedKmh) { p -> pushLog("maneuver: $p") }
            _state.update {
                it.copy(
                    busy = false,
                    banner = result.fold(
                        onSuccess = { r -> "Maneuver applied · ${r.newSpeedLimit} km/h · verified=${r.verified}" },
                        onFailure = { e -> "Maneuver failed: ${e.message}" },
                    ),
                )
            }
        }
    }

    fun onToggleKillSwitch() {
        viewModelScope.launch {
            if (_state.value.killSwitchEngaged) {
                repository.releaseKillSwitch { pushLog("killswitch: $it") }
                _state.update { it.copy(killSwitchEngaged = false, banner = "Containment released") }
            } else {
                repository.engageKillSwitch { pushLog("killswitch: $it") }
                _state.update { it.copy(killSwitchEngaged = true, banner = "KILLSWITCH ENGAGED") }
            }
        }
    }

    fun onBannerShown() = _state.update { it.copy(banner = null) }

    private fun pushLog(line: String) {
        _state.update { it.copy(log = (it.log + line).takeLast(LOG_CAPACITY)) }
    }

    private fun GattEvent.toLogLine(): String = when (this) {
        is GattEvent.Connected -> "link: connected ${address.takeLast(5)}"
        is GattEvent.ServicesDiscovered -> "gatt: $count services"
        is GattEvent.MtuNegotiated -> "gatt: mtu=$mtu"
        is GattEvent.NotificationEnabled -> "gatt: notify on"
        is GattEvent.FrameSent -> "tx: $bytes B"
        is GattEvent.FrameReceived -> "rx: $bytes B"
        is GattEvent.Warning -> "warn: $message"
        is GattEvent.Error -> "err: $message"
    }

    companion object {
        private const val LOG_CAPACITY = 120

        fun factory(repository: ScooterRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { DashboardViewModel(repository) }
        }
    }
}
