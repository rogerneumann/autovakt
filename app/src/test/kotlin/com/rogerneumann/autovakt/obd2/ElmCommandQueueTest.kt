package com.rogerneumann.autovakt.obd2

import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for ElmCommandQueue serialization guarantees and timeout behaviour.
 *
 * These cover the three failure modes we hit with OBDLink CX:
 *   1. CancellationException from a timed-out command must not break the queue.
 *   2. A 5000ms timeout must successfully carry a ~2.5s ATZ reset response.
 *   3. Commands must be strictly sequential — second send() must not fire until
 *      the first readResponse() completes (GATT one-op-at-a-time requirement).
 *
 * Uses runBlocking + real coroutine delays instead of virtual-time because
 * ElmCommandQueue hardcodes Dispatchers.IO internally. Timeouts are kept small
 * (≤ 2500ms) to stay within a reasonable test duration.
 */
class ElmCommandQueueTest {

    private val transport = mockk<TransportDelegate>(relaxed = true)
    private lateinit var queue: ElmCommandQueue

    @Before
    fun setUp() {
        queue = ElmCommandQueue(transport)
    }

    @After
    fun tearDown() {
        queue.stop()
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    fun `successful command returns transport response`() = runBlocking {
        coEvery { transport.send(any()) } just runs
        coEvery { transport.readResponse() } returns "OK>"

        assertEquals("OK>", queue.execute("ATE0"))
    }

    // ── Timeout parameter ─────────────────────────────────────────────────────

    @Test
    fun `5000ms timeout allows response that arrives in 2500ms`() = runBlocking {
        // Simulates ELM327 ATZ cold-start reset — can take up to ~2s on OBDLink CX.
        coEvery { transport.send(any()) } just runs
        coEvery { transport.readResponse() } coAnswers {
            delay(2500L)
            "ELM327 v1.5\r\n>"
        }

        val result = queue.execute("ATZ", 5000L)

        assertEquals("ELM327 v1.5\r\n>", result)
    }

    @Test
    fun `2000ms default timeout rejects same 2500ms response`() = runBlocking {
        coEvery { transport.send(any()) } just runs
        coEvery { transport.readResponse() } coAnswers {
            delay(2500L)
            "ELM327 v1.5\r\n>"
        }

        // Wrap in async so the CancellationException does not cancel runBlocking.
        val result = async { runCatching { queue.execute("ATZ") } }.await()

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull() is TimeoutCancellationException,
            "Expected TimeoutCancellationException, got: ${result.exceptionOrNull()}"
        )
    }

    // ── Queue recovery after timeout ──────────────────────────────────────────

    @Test
    fun `queue continues processing next command after a timed-out command`() = runBlocking {
        // This test guards the CancellationException fix in ElmBleTransport:
        // a timeout must fail only the one command — the queue must keep running.
        var callCount = 0
        coEvery { transport.send(any()) } just runs
        coEvery { transport.readResponse() } coAnswers {
            callCount++
            if (callCount == 1) { delay(5000L); "never>" } else "OK>"
        }

        // Both commands queued immediately; processed one at a time.
        val slow = async { runCatching { queue.execute("ATZ", 300L) } }
        val fast = async { queue.execute("ATE0") }

        assertTrue(slow.await().isFailure, "First command should fail with timeout")
        assertEquals("OK>", fast.await(), "Queue must process second command after recovery")
    }

    // ── Sequential execution ──────────────────────────────────────────────────

    @Test
    fun `second send does not fire until first readResponse completes`() = runBlocking {
        // Validates the GATT one-op-at-a-time contract: OBDLink CX rejects a new
        // write if the previous write's response has not been received.
        val events = mutableListOf<String>()
        val readStarted = CompletableDeferred<Unit>()
        val readRelease = CompletableDeferred<Unit>()

        coEvery { transport.send("CMD1") } answers { events += "send:CMD1" }
        coEvery { transport.send("CMD2") } answers { events += "send:CMD2" }
        coEvery { transport.readResponse() } coAnswers {
            if ("read:started" !in events) {
                events += "read:started"
                readStarted.complete(Unit)
                readRelease.await()
                events += "read:done"
            }
            "OK>"
        }

        // CMD1 starts; we wait until its readResponse is in-flight.
        val j1 = async { queue.execute("CMD1") }
        readStarted.await()

        // Submit CMD2 while CMD1 is still inside readResponse.
        val j2 = async { queue.execute("CMD2") }
        delay(100L)

        assertTrue("send:CMD2" !in events,
            "CMD2 send fired before CMD1 read completed — queue violated sequential contract. Events: $events")

        // Release CMD1's read, let everything finish.
        readRelease.complete(Unit)
        j1.await()
        j2.await()

        val readDoneIdx = events.indexOf("read:done")
        val send2Idx    = events.indexOf("send:CMD2")
        assertTrue(send2Idx > readDoneIdx,
            "send:CMD2 ($send2Idx) must follow read:done ($readDoneIdx). Events: $events")
    }
}
