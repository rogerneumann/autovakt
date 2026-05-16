package com.rogerneumann.autovakt.data

import org.junit.Test

/**
 * VehicleProfileHub compilation tests.
 * Full integration tests with real file I/O are best done manually or via instrumented tests.
 */
class VehicleProfileHubTest {

    @Test
    fun testVehicleProfileHubCompiles() {
        // Verify the class exists and can be referenced
        val klass = VehicleProfileHub::class
        assert(klass.simpleName == "VehicleProfileHub")
    }
}
