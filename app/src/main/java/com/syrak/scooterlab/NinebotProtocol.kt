package com.syrak.scooterlab.core.protocol

/**
 * Frame factory + addressing/command vocabulary for the Ninebot serial protocol.
 *
 * Addresses (nodes on the internal bus):
 *   0x20 ESC  — motor controller (speed, current, KERS, limits)
 *   0x21 BLE  — bluetooth module (auth, pairing, name)
 *   0x22 BMS  — battery management (voltage, cells, temperature)
 *   0x3D APP  — the phone / master
 *
 * Commands:
 *   0x01 READ  — read one or more registers
 *   0x02 WRITE — write one or more registers
 *
 * NOTE: address/command assignments are stable across the Ninebot family, but the
 * *register* map is firmware-specific. See [RegisterMap] before issuing writes.
 */
object NinebotProtocol {

    const val HEADER_0 = 0x5A
    const val HEADER_1 = 0xA5

    const val ADDR_ESC = 0x20
    const val ADDR_BLE = 0x21
    const val ADDR_BMS = 0x22
    const val ADDR_APP = 0x3D

    const val CMD_READ = 0x01
    const val CMD_WRITE = 0x02

    /** Read a single register: payload = [register]. */
    fun buildRead(destination: Int, register: Int): NinebotFrame =
        NinebotFrame(ADDR_APP, destination, CMD_READ, byteArrayOf(register.toByte()))

    /** Read a contiguous register range: payload = [startRegister, count]. */
    fun buildReadRange(destination: Int, startRegister: Int, count: Int): NinebotFrame =
        NinebotFrame(ADDR_APP, destination, CMD_READ, byteArrayOf(startRegister.toByte(), count.toByte()))

    /** Write a register: payload = [register, data...]. */
    fun buildWrite(destination: Int, register: Int, data: ByteArray): NinebotFrame {
        val payload = ByteArray(1 + data.size)
        payload[0] = register.toByte()
        data.copyInto(payload, destinationIndex = 1)
        return NinebotFrame(ADDR_APP, destination, CMD_WRITE, payload)
    }

    /** Convenience for writing a single byte register. */
    fun buildWriteByte(destination: Int, register: Int, value: Int): NinebotFrame =
        buildWrite(destination, register, byteArrayOf(value.toByte()))

    /** Convenience for writing a little-endian 32-bit register. */
    fun buildWriteInt(destination: Int, register: Int, value: Int): NinebotFrame =
        buildWrite(
            destination,
            register,
            byteArrayOf(
                (value and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                ((value ushr 16) and 0xFF).toByte(),
                ((value ushr 24) and 0xFF).toByte(),
            ),
        )

    /** Extract the register byte from a READ/WRITE response payload, if present. */
    fun responseRegister(frame: NinebotFrame): Int? =
        frame.payload.firstOrNull()?.toInt()?.and(0xFF)

    /** Extract the value bytes (everything after the register byte) from a response. */
    fun responseValue(frame: NinebotFrame): ByteArray =
        if (frame.payload.size <= 1) ByteArray(0) else frame.payload.copyOfRange(1, frame.payload.size)

    fun u16LE(bytes: ByteArray, offset: Int = 0): Int =
        ((bytes[offset].toInt() and 0xFF)) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    fun i16LE(bytes: ByteArray, offset: Int = 0): Int {
        val v = u16LE(bytes, offset)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    fun u32LE(bytes: ByteArray, offset: Int = 0): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}
