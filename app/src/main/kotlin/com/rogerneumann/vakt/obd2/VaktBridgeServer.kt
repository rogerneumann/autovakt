package com.rogerneumann.vakt.obd2

import com.rogerneumann.vakt.data.OBD2Repository
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vakt Bridge: A local TCP server that emulates a WiFi ELM327 adapter.
 * Allows other apps (like ABRP) to connect to Vakt and receive vehicle data.
 */
@Singleton
class VaktBridgeServer @Inject constructor(
    private val repository: OBD2Repository,
    private val queue: ElmCommandQueue
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    /**
     * Starts the TCP server on the specified port.
     */
    fun start(port: Int = 35000) {
        if (isRunning) return
        isRunning = true
        
        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                while (isActive) {
                    val client = serverSocket?.accept()
                    if (client != null) {
                        handleClient(client)
                    }
                }
            } catch (e: Exception) {
                isRunning = false
            }
        }
    }

    /**
     * Handles an individual client connection (e.g., ABRP).
     */
    private fun handleClient(socket: Socket) {
        scope.launch {
            socket.use { s ->
                val reader = s.getInputStream().bufferedReader()
                val writer = s.getOutputStream().bufferedWriter()
                
                try {
                    while (isActive && !s.isClosed) {
                        val command = reader.readLine() ?: break
                        
                        // Bridge Logic: 
                        // If it's a command Vakt already knows (like SOC), return cached data.
                        // Otherwise, forward it to the real ELM327 via the queue.
                        val response = processCommand(command)
                        
                        writer.write(response + "\r>")
                        writer.flush()
                    }
                } catch (e: Exception) {
                    // Client disconnected
                }
            }
        }
    }

    private suspend fun processCommand(command: String): String {
        // TODO: Map standard PIDs to Vakt's cached data for zero-latency response
        return "OK" 
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        serverSocket?.close()
    }
}
