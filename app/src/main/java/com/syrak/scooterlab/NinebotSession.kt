package com.syrak.scooterlab.core.protocol

import com.syrak.scooterlab.core.ble.BleManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Correlates outbound requests with inbound responses over a live [BleManager].
 *
 * The BLE link is a single bidirectional stream, so we demultiplex by
 * (source, command) and use a small replay buffer to close the subscribe/write
 * race window.
 */
class NinebotSession(
    private val ble: BleManager,
    private val scope: CoroutineScope,
) {
    private val responses = MutableSharedFlow<NinebotFrame>(
        replay = 16,
        extraBufferCapacity = 128,
    )

    init {
        scope.launch {
            ble.incoming.collect { bytes ->
                NinebotFrame.decode(bytes)?.let { responses.tryEmit(it) }
            }
        }
    }

    /**
     * Send [request] and await a response of the same command from [expectFrom].
     * Returns null on timeout (never throws for a missing reply).
     */
    suspend fun request(
        request: NinebotFrame,
        expectFrom: Int = request.destination,
        timeoutMs: Long = 1_500L,
    ): NinebotFrame? = withTimeoutOrNull(timeoutMs) {
        val matcher = scope.async {
            responses.filter { it.source == expectFrom && it.command == request.command }.first()
        }
        ble.write(request.encode())
        matcher.await()
    }

    /** Fire-and-forget write (no response expected). */
    suspend fun send(frame: NinebotFrame) {
        ble.write(frame.encode())
    }

    /** Read a single register and return its raw value bytes, or null. */
    suspend fun readRegister(
        destination: Int,
        register: Int,
        timeoutMs: Long = 1_500L,
    ): ByteArray? {
        val frame = NinebotProtocol.buildRead(destination, register)
        val response = request(frame, expectFrom = destination, timeoutMs = timeoutMs) ?: run {
            Timber.w("No response for read of %s", RegisterMap.nameOf(register))
            return null
        }
        return NinebotProtocol.responseValue(response)
    }
}
