package com.rogerneumann.autovakt.data

import com.rogerneumann.autovakt.media.MediaRemoteManager
import com.rogerneumann.autovakt.obd2.AutoVaktBridgeServer
import com.rogerneumann.autovakt.obd2.ConnectionState
import com.rogerneumann.autovakt.obd2.ElmCommandQueue
import com.rogerneumann.autovakt.obd2.GmProtocolHandler
import com.rogerneumann.autovakt.obd2.TransportDelegate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test

/**
 * Verifies that the ELM327 initialisation sequence in OBD2Repository uses the
 * correct command ordering and timeouts.
 *
 * Key assertion: ATZ must be sent with a 5000ms timeout, not the 2000ms default.
 * ELM327 cold-start reset takes up to ~2s on OBDLink CX; the 2000ms default is
 * too tight and caused intermittent TimeoutCancellationException failures in testing.
 */
class OBD2RepositoryInitTest {

    private val mockTransport       = mockk<TransportDelegate>(relaxed = true)
    private val mockQueue           = mockk<ElmCommandQueue>(relaxed = true)
    private val mockProtocolHandler = mockk<GmProtocolHandler>(relaxed = true)
    private val mockTripRepository  = mockk<TripRepository>(relaxed = true)
    private val mockProfileManager  = mockk<VehicleProfileManager>(relaxed = true)
    private val mockProfileHub      = mockk<VehicleProfileHub>(relaxed = true)
    private val mockLayoutManager   = mockk<VehicleLayoutManager>(relaxed = true)
    private val mockPidCache        = mockk<PidCache>(relaxed = true)
    private val mockBridgeServer    = mockk<AutoVaktBridgeServer>(relaxed = true)
    private val mockMediaManager    = mockk<MediaRemoteManager>(relaxed = true)

    private lateinit var repository: OBD2Repository

    @Before
    fun setUp() {
        // Transport appears connected immediately so runLiveLoop() doesn't poll.
        every { mockTransport.connectionState } returns MutableStateFlow(ConnectionState.Connected)

        // Queue returns safe defaults; ATZ signals the test via atzCalled.
        coEvery { mockQueue.execute(any()) } returns "OK>"
        coEvery { mockQueue.execute(any(), any()) } returns "OK>"

        // Simplest profile path: no VIN, returns DEFAULT (empty initCommands list).
        coEvery { mockProtocolHandler.discoverVin() } returns null
        every { mockProfileManager.getActiveProfile() } returns VehicleProfile.DEFAULT
        every { mockLayoutManager.resolveKey(any(), any(), any()) } returns "gauge_layout_global"

        repository = OBD2Repository(
            mockTransport, mockQueue, mockProtocolHandler, mockTripRepository,
            mockProfileManager, mockProfileHub, mockLayoutManager, mockPidCache,
            mockBridgeServer, mockMediaManager
        )
    }

    @Test
    fun `ATZ is sent with 5000ms timeout not the 2000ms default`() = runBlocking {
        val atzCalled = CompletableDeferred<Unit>()
        coEvery { mockQueue.execute("ATZ", 5000L) } coAnswers {
            atzCalled.complete(Unit)
            "ELM327 v1.5>"
        }

        repository.start()

        // Wait up to 5s for the IO coroutine to reach ATZ; fail fast if it doesn't.
        withTimeout(5000L) { atzCalled.await() }

        coVerify(exactly = 1) { mockQueue.execute("ATZ", 5000L) }
        repository.stop()
    }

    @Test
    fun `ATZ is sent before ATE0`() = runBlocking {
        val atzCalled  = CompletableDeferred<Unit>()
        val ate0Called = CompletableDeferred<Unit>()

        coEvery { mockQueue.execute("ATZ", 5000L) } coAnswers {
            atzCalled.complete(Unit)
            "ELM327 v1.5>"
        }
        coEvery { mockQueue.execute("ATE0") } coAnswers {
            ate0Called.complete(Unit)
            "OK>"
        }

        repository.start()

        withTimeout(5000L) { atzCalled.await() }
        withTimeout(5000L) { ate0Called.await() }

        coVerifyOrder {
            mockQueue.execute("ATZ", 5000L)
            mockQueue.execute("ATE0")
        }
        repository.stop()
    }
}
