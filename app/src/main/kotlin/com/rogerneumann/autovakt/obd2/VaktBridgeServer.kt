package com.rogerneumann.autovakt.obd2

import com.rogerneumann.autovakt.data.PidCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vakt Bridge: A local TCP server that emulates a WiFi ELM327 adapter.
 * Allows other apps (like ABRP, Car Scanner) to connect to Vakt and
 * receive vehicle data via a shared-polling cache-backed architecture.
 *
 * Architecture:
 * - AT commands pass through to ElmCommandQueue directly (no caching).
 * - PID commands are served from PidCache if fresh (< 3s); otherwise the PID
 *   is registered in bridgeRequestedPids for OBD2Repository to poll next cycle,
 *   then the client waits up to 4s for a cache entry to appear.
 */
@Singleton
class VaktBridgeServer @Inject constructor(
    private val queue: ElmCommandQueue,
    private val pidCache: PidCache
) {
    // Persistent scope — never cancelled; only individual jobs are.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    /**
     * PIDs actively requested by connected bridge clients.
     * OBD2Repository unions this set with its display-slot PIDs each cycle.
     */
    val bridgeRequestedPids: MutableSet<String> = CopyOnWriteArraySet()

    private val _activeClientCount = MutableStateFlow(0)
    val activeClientCount: StateFlow<Int> = _activeClientCount.asStateFlow()

    /**
     * If false, incoming connections are immediately rejected to maximize
     * bandwidth for Vakt's internal UI.
     */
    var isBridgeEnabled = true

    /**
     * The actual port the bridge is currently listening on.
     * Set when start() successfully binds to a port.
     */
    var activePort: Int = 0

    /**
     * Starts the TCP server. Safe to call multiple times — cancels any
     * existing server job and closes the old socket before rebinding to
     * prevent BindException on restart.
     */
    fun start(port: Int = 35000) {
        serverJob?.cancel()
        closeServerSocket()

        serverJob = scope.launch {
            try {
                var actualPort = port
                try {
                    serverSocket = ServerSocket(port)
                } catch (e: BindException) {
                    // Port 35000 in use; try fallback port 35001
                    try {
                        actualPort = 35001
                        serverSocket = ServerSocket(35001)
                    } catch (e: Exception) {
                        // Both ports failed — let outer catch handle it
                        throw e
                    }
                }
                activePort = actualPort
                Log.i("VaktBridge", "Bridge listening on port $actualPort")
                while (isActive) {
                    val client = try {
                        serverSocket?.accept()
                    } catch (e: Exception) {
                        break
                    } ?: break

                    if (isBridgeEnabled) {
                        launch { handleClient(client) }
                    } else {
                        runCatching { client.close() }
                    }
                }
            } catch (e: Exception) {
                // Startup failed (e.g., both ports in use) — serverJob ends cleanly.
                activePort = 0
            }
        }
    }

    /**
     * Handles an individual TCP client (e.g., ABRP, Car Scanner).
     *
     * AT commands bypass the cache and go straight to the ELM queue.
     * PID commands are served from PidCache when fresh; otherwise registered
     * for polling and awaited up to 4s.
     *
     * "No Massaging" rule: raw bytes are forwarded unmodified.
     */
    private suspend fun handleClient(socket: Socket) {
        // Per-client set of PIDs it has registered
        val clientPids = CopyOnWriteArraySet<String>()
        _activeClientCount.value++
        socket.use { s ->
            val reader = s.getInputStream().bufferedReader()
            val writer = s.getOutputStream().bufferedWriter()
            try {
                while (currentCoroutineContext().isActive && !s.isClosed) {
                    val command = reader.readLine()?.trim() ?: break
                    if (command.isEmpty()) continue

                    val response = if (command.uppercase().startsWith("AT")) {
                        // AT commands: pass through raw
                        queue.execute(command)
                    } else {
                        // PID command: use cache-backed shared polling
                        val pidKey = command.uppercase().replace(" ", "")
                        clientPids.add(pidKey)
                        handlePidRequest(pidKey, clientPids)
                            ?: queue.execute(command)  // fallback: direct pass-through on timeout
                    }

                    writer.write(response + "\r>")
                    writer.flush()
                }
            } catch (e: Exception) {
                // Client disconnected — exit cleanly.
            } finally {
                // Remove this client's registered PIDs from the shared set
                bridgeRequestedPids.removeAll(clientPids)
                clientPids.clear()
                _activeClientCount.value = (_activeClientCount.value - 1).coerceAtLeast(0)
            }
        }
    }

    /**
     * Serves a PID request from a bridge client:
     * 1. Check cache — return immediately if entry is fresh (< 3s).
     * 2. Register PID for polling by OBD2Repository.
     * 3. Wait up to 4s for OBD2Repository to populate the cache.
     * 4. Remove PID from bridgeRequestedPids once served (or on timeout).
     */
    private suspend fun handlePidRequest(
        pidKey: String,
        clientPids: MutableSet<String>,
        timeoutMs: Long = 4000L
    ): String? {
        // Fast path: cache hit
        pidCache.get(pidKey)?.let { return it }

        // Register for next poll cycle
        bridgeRequestedPids.add(pidKey)
        clientPids.add(pidKey)

        // Wait for OBD2Repository to populate the cache
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val result = pidCache.get(pidKey)
            if (result != null) {
                bridgeRequestedPids.remove(pidKey)
                return result
            }
            delay(100L)
        }

        // Timeout — remove from bridge set so poller doesn't keep scheduling it
        bridgeRequestedPids.remove(pidKey)
        return null
    }

    /**
     * Stops the server and all active client connections.
     * The scope itself is preserved so start() can be called again.
     */
    fun stop() {
        serverJob?.cancel()
        serverJob = null
        closeServerSocket()
        bridgeRequestedPids.clear()
        _activeClientCount.value = 0
    }

    private fun closeServerSocket() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }
}
