package com.syrak.scooterlab.core.util

/** Compact hex helpers for the diagnostics console and unit tests. */
object Hex {

    private const val DIGITS = "0123456789ABCDEF"

    fun encode(bytes: ByteArray, separator: String = " "): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size * (2 + separator.length))
        for ((i, b) in bytes.withIndex()) {
            if (i > 0) sb.append(separator)
            val v = b.toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    fun decode(hex: String): ByteArray {
        val clean = hex.filter { !it.isWhitespace() && it != ':' }
        require(clean.length % 2 == 0) { "Hex string must have an even length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

fun ByteArray.toHex(separator: String = " "): String = Hex.encode(this, separator)
