package com.syrak.scooterlab.core.ble

import java.util.UUID

/**
 * Wire-level BLE identifiers used by the supported scooter families.
 *
 * Ninebot / Segway / Xiaomi controllers expose the Nordic-UART-style GATT layout:
 *  - a single service carrying one write characteristic (app -> controller) and
 *    one notify characteristic (controller -> app).
 *
 * Some Xiaomi firmwares advertise the Xiaomi service instead; both are scanned.
 */
object BleConstants {

    /** Nordic UART Service — the transport used by Ninebot/Segway/Xiaomi ESCs. */
    val UART_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    /** App -> controller (Write / Write-No-Response). */
    val UART_WRITE_CHAR: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    /** Controller -> app (Notify). */
    val UART_NOTIFY_CHAR: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    /** Xiaomi proprietary service (advertised by some M365/1S/Pro units). */
    val XIAOMI_SERVICE: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")

    /** Standard Client Characteristic Configuration Descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Service UUIDs used as scan filters. */
    val SCAN_SERVICE_FILTERS: List<UUID> = listOf(UART_SERVICE, XIAOMI_SERVICE)

    /** Advertised-name prefixes used for client-side brand classification. */
    val NAME_PREFIXES: List<String> = listOf(
        "Mi Scooter", "MIScooter", "Mi Electric", "Ninebot", "Segway", "NAVEE", "Navee",
        "M365", "1S", "Pro 2", "ES", "MAX", "F", "G30", "D", "P65",
    )

    /** Requested ATT MTU. 247 yields a 244-byte usable payload; controllers may cap lower. */
    const val REQUESTED_MTU = 247

    /** Conservative default when the controller refuses MTU negotiation. */
    const val DEFAULT_MTU = 23

    /** ATT overhead subtracted from MTU to obtain the max single-write payload. */
    const val ATT_HEADER_BYTES = 3

    /** Guard rails for connection behaviour. */
    const val CONNECT_TIMEOUT_MS = 12_000L
    const val OPERATION_TIMEOUT_MS = 4_000L
    const val MAX_WRITE_RETRIES = 2
}
