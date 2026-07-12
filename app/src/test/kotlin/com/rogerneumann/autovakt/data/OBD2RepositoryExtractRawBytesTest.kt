package com.rogerneumann.autovakt.data

import com.rogerneumann.autovakt.media.MediaRemoteManager
import com.rogerneumann.autovakt.obd2.AutoVaktBridgeServer
import com.rogerneumann.autovakt.obd2.ElmCommandQueue
import com.rogerneumann.autovakt.obd2.GmProtocolHandler
import com.rogerneumann.autovakt.obd2.TransportDelegate
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [OBD2Repository.extractRawBytes] - the multi-frame reassembly and
 * hex-payload parser. These lock in the protocol lessons from session-notes.md.
 */
class OBD2RepositoryExtractRawBytesTest {

    private lateinit var repository: OBD2Repository

    @Before
    fun setUp() {
        repository = OBD2Repository(
            mockk<TransportDelegate>(relaxed = true),
            mockk<ElmCommandQueue>(relaxed = true),
            mockk<GmProtocolHandler>(relaxed = true),
            mockk<TripRepository>(relaxed = true),
            mockk<VehicleProfileManager>(relaxed = true),
            mockk<VehicleProfileHub>(relaxed = true),
            mockk<VehicleLayoutManager>(relaxed = true),
            mockk<PidCache>(relaxed = true),
            mockk<AutoVaktBridgeServer>(relaxed = true),
            mockk<MediaRemoteManager>(relaxed = true)
        )
    }

    @Test
    fun `single-frame happy path returns correct payload bytes`() {
        val result = repository.extractRawBytes("62 8334 64", "228334")
        assertArrayEquals(byteArrayOf(0x64), result)
    }

    @Test
    fun `multi-frame ATCAF1 response with line-number prefixes is reassembled correctly`() {
        val result = repository.extractRawBytes("0:62833401\n1:0203", "228334")
        assertArrayEquals(byteArrayOf(1, 2, 3), result)
    }

    @Test
    fun `negative response returns null not a crash`() {
        val result = repository.extractRawBytes("7F2231", "228334")
        assertNull(result)
    }

    @Test
    fun `response with ATL0 prompt on same line is parsed correctly`() {
        val result = repository.extractRawBytes("6283340A>", "228334")
        assertArrayEquals(byteArrayOf(0x0A), result)
    }

    @Test
    fun `odd-length payload returns null`() {
        val result = repository.extractRawBytes("628334A", "228334")
        assertNull(result)
    }

    @Test
    fun `ATH1-style header-prefixed response returns null - this is why ATH1 is forbidden`() {
        val result = repository.extractRawBytes("7E462833464", "228334")
        assertNull(result)
    }
}
