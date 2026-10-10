package com.syrak.scooterlab.core.protocol

/**
 * CRC-16/CCITT (a.k.a. XMODEM) used by the Ninebot/Segway serial framing.
 *
 *  poly = 0x1021, init = 0x0000, refin = false, refout = false, xorout = 0x0000.
 */
object Crc16 {

    private const val POLY = 0x1021

    fun xmodem(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0x0000
        val end = offset + length
        for (i in offset until end) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    ((crc shl 1) xor POLY) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }
}
