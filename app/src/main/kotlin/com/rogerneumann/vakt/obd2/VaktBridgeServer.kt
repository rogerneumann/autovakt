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
     * If false, the server will reject connections to maximize polling speed 
     * for Vakt's internal UI.
     */
    var isBridgeEnabled = true

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
                        if (isBridgeEnabled) {
                            handleClient(client)
                        } else {
                            client.close()
                        }
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
                        val command = reader.readLine()?.trim() ?: break
                        if (command.isEmpty()) continue
                        
                        // Respecting the "No Massaging" rule: 
                        // Forward the raw command to the ELM327 and return the raw response.
                        val response = queue.execute(command)
                        
                        writer.write(response + "\r>")
                        writer.flush()
                    }
                } catch (e: Exception) {
                    // Client disconnected
                }
            }
        }
    }


    fun stop() {
        isRunning = false
        scope.cancel()
        serverSocket?.close()
    }
}
