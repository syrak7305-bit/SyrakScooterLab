package com.syrak.scooterlab.core.ble

/**
 * Recognised scooter families. Brand classification drives which register map,
 * auth scheme and firmware channel are used downstream.
 */
enum class ScooterBrand(val displayName: String, val modelFamily: String) {
    NINEBOT("Ninebot", "Segway-Ninebot ESC"),
    SEGWAY("Segway", "Segway ESC"),
    XIAOMI("Xiaomi", "M365 / 1S / Pro"),
    NAVEE("Navee", "Navee ESC"),
    UNKNOWN("Unknown", "Unclassified controller");

    companion object {
        fun classify(name: String?): ScooterBrand {
            val n = name?.lowercase() ?: return UNKNOWN
            return when {
                "ninebot" in n -> NINEBOT
                "segway" in n -> SEGWAY
                "navee" in n -> NAVEE
                "mi scooter" in n || "m365" in n || n.startsWith("mi ") -> XIAOMI
                else -> UNKNOWN
            }
        }
    }
}

/** A scooter discovered during a BLE scan. */
data class DiscoveredScooter(
    val address: String,
    val name: String?,
    val rssi: Int,
    val brand: ScooterBrand,
    val lastSeenMs: Long,
) {
    /** Stable identity for list diffing. */
    val id: String get() = address
}

/**
 * Finite connection lifecycle. Modelled as a sealed hierarchy so the UI can
 * exhaustively render every state — no silent fall-throughs.
 */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Scanning : ConnectionState
    data class Connecting(val address: String) : ConnectionState
    data class Discovering(val address: String) : ConnectionState
    data class Authenticating(val address: String) : ConnectionState
    data class Ready(
        val address: String,
        val name: String?,
        val brand: ScooterBrand,
        val mtu: Int,
    ) : ConnectionState
    data class Failed(val address: String?, val reason: String) : ConnectionState
    data object Disconnected : ConnectionState
}

/** Structured telemetry emitted by the GATT layer for the diagnostics console. */
sealed interface GattEvent {
    data class Connected(val address: String) : GattEvent
    data class ServicesDiscovered(val count: Int) : GattEvent
    data class MtuNegotiated(val mtu: Int) : GattEvent
    data class NotificationEnabled(val uuid: String) : GattEvent
    data class FrameSent(val bytes: Int) : GattEvent
    data class FrameReceived(val bytes: Int) : GattEvent
    data class Warning(val message: String) : GattEvent
    data class Error(val message: String, val cause: Throwable? = null) : GattEvent
}
