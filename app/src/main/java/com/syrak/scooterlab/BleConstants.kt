package com.syrak.scooterlab.core.ble

import com.syrak.scooterlab.BleUuids
import java.util.UUID

/**
 * Wire-level BLE identifiers used by the LEGACY BLE path.
 *
 * PHASE 16 — WICHTIG (belegter Widerspruch, jetzt dokumentiert):
 * Diese Datei gehoert zum alten `BleManager`-Stack (Compose-Pfad). Der aktive,
 * von `MainActivity` benutzte Stack ist `BleEngine` und verwendet fuer Ninebot
 * `0000e003` / `0000fff1` / `0000fff2` sowie fuer Navee `0000ffe0` / `0000ffe1`
 * / `0000ffe2`.
 *
 * Frueher stand hier die Behauptung, Nordic-UART sei "der Transport, den
 * Ninebot/Segway/Xiaomi ESCs verwenden". Das widersprach `BleEngine`. Beide
 * Werte bleiben unveraendert erhalten (nichts wird erfunden oder umgeschrieben),
 * aber die Herkunft ist jetzt in [BleUuids] eindeutig als
 * `LEGACY_UNCONFIRMED` klassifiziert. Es gibt ab Phase 16 nur noch EINE
 * Textdefinition pro UUID; diese Datei verweist darauf.
 */
object BleConstants {

    /**
     * Nordic UART Service (Legacy-Pfad).
     * @see BleUuids.LEGACY_UART_SERVICE
     */
    val UART_SERVICE: UUID = UUID.fromString(BleUuids.LEGACY_UART_SERVICE)

    /** App -> controller (Write / Write-No-Response). Legacy-Pfad. */
    val UART_WRITE_CHAR: UUID = UUID.fromString(BleUuids.LEGACY_UART_WRITE)

    /** Controller -> app (Notify). Legacy-Pfad. */
    val UART_NOTIFY_CHAR: UUID = UUID.fromString(BleUuids.LEGACY_UART_NOTIFY)

    /** Xiaomi proprietary service (Legacy-Scanfilter). */
    val XIAOMI_SERVICE: UUID = UUID.fromString(BleUuids.LEGACY_XIAOMI_SERVICE)

    /** Standard Client Characteristic Configuration Descriptor. */
    val CCCD: UUID = UUID.fromString(BleUuids.CCCD)

    /** Service UUIDs used as scan filters (Legacy-Pfad). */
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
