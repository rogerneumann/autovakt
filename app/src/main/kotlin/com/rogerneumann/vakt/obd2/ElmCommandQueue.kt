package com.rogerneumann.vakt.obd2

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.rogerneumann.vakt.data.VaktLiveData

/**
 * Ensures commands are sent sequentially to the ELM327.
 * Manages the polling loop and command timeouts.
 */
class ElmCommandQueue(
    private val transport: ElmBluetoothTransport
) {
    private val commandChannel = Channel<CommandRequest>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _isPolling = MutableStateFlow(false)
    val isPolling: StateFlow<Boolean> = _isPolling

    private val _rawTraffic = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)
    val rawTraffic = _rawTraffic.asSharedFlow()

    init {
        startProcessing()
    }

    private fun startProcessing() {
        scope.launch {
            for (request in commandChannel) {
                try {
                    withTimeout(request.timeoutMs) {
                        transport.send(request.command)
                        val response = transport.readResponse()
                        
                        // Immediately split: Send to requester AND broadcast raw for the Bridge
                        _rawTraffic.emit(request.command to response)
                        request.onResult(Result.success(response))
                    }
                } catch (e: Exception) {
                    request.onResult(Result.failure(e))
                }
            }
        }
    }

    /**
     * Executes a command and returns the result.
     */
    suspend fun execute(command: String, timeoutMs: Long = 2000L): String {
        return suspendCancellableCoroutine { continuation ->
            val request = CommandRequest(command, timeoutMs) { result ->
                if (continuation.isActive) {
                    if (result.isSuccess) {
                        continuation.resumeWith(Result.success(result.getOrThrow()))
                    } else {
                        continuation.resumeWith(Result.failure(result.exceptionOrNull()!!))
                    }
                }
            }
            commandChannel.trySend(request)
        }
    }

    fun stop() {
        scope.cancel()
    }
}

data class CommandRequest(
    val command: String,
    val timeoutMs: Long,
    val onResult: (Result<String>) -> Unit
)
