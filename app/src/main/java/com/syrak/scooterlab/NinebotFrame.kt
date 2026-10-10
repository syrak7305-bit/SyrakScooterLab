package com.syrak.scooterlab.core.protocol

import com.syrak.scooterlab.core.util.toHex

/**
 * A single Ninebot serial frame.
 *
 *  ┌──────┬──────┬──────┬──────┬──────┬──────┬───────────┬─────────┐
 *  │ 0x5A │ 0xA5 │ LEN  │ SRC  │ DST  │ CMD  │  PAYLOAD  │  CRC16  │
 *  └──────┴──────┴──────┴──────┴──────┴──────┴───────────┴─────────┘
 *    [0]    [1]    [2]    [3]    [4]    [5]    [6..]      [last 2]
 *
 *  LEN = 3 (SRC+DST+CMD) + payload size.
 *  CRC16 (little-endian) is computed over bytes [2 .. 6+payload) — i.e. LEN through payload.
 */
data class NinebotFrame(
    val source: Int,
    val destination: Int,
    val command: Int,
    val payload: ByteArray,
) {
    init {
        require(source in 0..0xFF) { "source out of range" }
        require(destination in 0..0xFF) { "destination out of range" }
        require(command in 0..0xFF) { "command out of range" }
    }

    val length: Int get() = 3 + payload.size

    fun encode(): ByteArray {
        val len = length
        val out = ByteArray(2 + 1 + len + 2)
        out[0] = NinebotProtocol.HEADER_0.toByte()
        out[1] = NinebotProtocol.HEADER_1.toByte()
        out[2] = len.toByte()
        out[3] = source.toByte()
        out[4] = destination.toByte()
        out[5] = command.toByte()
        payload.copyInto(out, destinationIndex = 6)
        val crc = Crc16.xmodem(out, offset = 2, length = 1 + len)
        out[6 + payload.size] = (crc and 0xFF).toByte()
        out[7 + payload.size] = ((crc ushr 8) and 0xFF).toByte()
        return out
    }

    fun hex(): String = encode().toHex()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NinebotFrame) return false
        return source == other.source &&
            destination == other.destination &&
            command == other.command &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = source
        result = 31 * result + destination
        result = 31 * result + command
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        /**
         * Parse a raw notification payload into a frame, validating header, length and CRC.
         * Returns null on any structural or checksum failure (never throws on bad input).
         */
        fun decode(bytes: ByteArray): NinebotFrame? {
            if (bytes.size < 7) return null
            if ((bytes[0].toInt() and 0xFF) != NinebotProtocol.HEADER_0) return null
            if ((bytes[1].toInt() and 0xFF) != NinebotProtocol.HEADER_1) return null

            val len = bytes[2].toInt() and 0xFF
            val total = 2 + 1 + len + 2
            if (bytes.size < total) return null

            val expected = Crc16.xmodem(bytes, offset = 2, length = 1 + len)
            val actual = (bytes[6 + (len - 3)].toInt() and 0xFF) or
                ((bytes[7 + (len - 3)].toInt() and 0xFF) shl 8)
            if (expected != actual) return null

            val payloadSize = len - 3
            val payload = if (payloadSize > 0) bytes.copyOfRange(6, 6 + payloadSize) else ByteArray(0)
            return NinebotFrame(
                source = bytes[3].toInt() and 0xFF,
                destination = bytes[4].toInt() and 0xFF,
                command = bytes[5].toInt() and 0xFF,
                payload = payload,
            )
        }
    }
}
