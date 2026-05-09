package com.rogerneumann.vakt.obd2

import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vakt Bridge: A local TCP server that emulates a WiFi ELM327 adapter.
 * Allows other apps (like ABRP) to connect to Vakt and receive vehicle data.
 *
 * FIX: Uses a replaceable serverJob so stop()/start() cycles don't destroy
 * the coroutine scope. Each client gets its own child coroutine so the
 * accept loop stays alive for concurrent connections.
 */
@Singleton
class VaktBridgeServer @Inject constructor(
    private val queue: ElmCommandQueue
) {
    // Persistent scope — never cancelled; only individual jobs are.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    /**
     * If false, incoming connections are immediately rejected to maximize
     * bandwidth for Vakt's internal UI.
     */
    var isBridgeEnabled = true

    /**
     * Starts the TCP server. Safe to call multiple times — cancels any
     * existing server job and closes the old socket before rebinding to
     * prevent BindException on restart.
     */
    fun start(port: Int = 35000) {
        // Cancel previous run cleanly before rebinding.
        serverJob?.cancel()
        closeServerSocket()

        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                while (isActive) {
                    val client = try {
                        serverSocket?.accept()
                    } catch (e: Exception) {
                        break // Socket closed or cancelled
                    } ?: break

                    if (isBridgeEnabled) {
                        // Each client runs concurrently as a child of serverJob.
                        launch { handleClient(client) }
                    } else {
                        runCatching { client.close() }
                    }
                }
            } catch (e: Exception) {
                // Startup failed (e.g., port in use) — serverJob ends cleanly.
            }
        }
    }

    /**
     * Handles an individual TCP client (e.g., ABRP).
     * "No Massaging" rule: raw command → ELM327 → raw response, no transformation.
     */
    private suspend fun handleClient(socket: Socket) {
        socket.use { s ->
            val reader = s.getInputStream().bufferedReader()
            val writer = s.getOutputStream().bufferedWriter()
            try {
                while (currentCoroutineContext().isActive && !s.isClosed) {
                    val command = reader.readLine()?.trim() ?: break
                    if (command.isEmpty()) continue
                    val response = queue.execute(command)
                    writer.write(response + "\r>")
                    writer.flush()
                }
            } catch (e: Exception) {
                // Client disconnected — exit cleanly.
            }
        }
    }

    /**
     * Stops the server and all active client connections.
     * The scope itself is preserved so start() can be called again.
     */
    fun stop() {
        serverJob?.cancel()
        serverJob = null
        closeServerSocket()
    }

    private fun closeServerSocket() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }
}
