package com.rogerneumann.autovakt.abrp

import android.content.Context
import android.content.SharedPreferences
import com.rogerneumann.autovakt.data.AutoVaktLiveData
import com.rogerneumann.autovakt.obd2.ConnectionState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AbrpReporter.
 *
 * HTTP calls are avoided by testing the skip-conditions and the is_charging logic
 * directly — either via token/soc guard paths (no network needed) or by calling
 * the internal isCharging() helper directly (pure logic, no I/O).
 */
class AbrpReporterTest {

    private lateinit var reporter: AbrpReporter

    // Minimal SharedPreferences/Context doubles so the reporter can be instantiated
    // without Robolectric — we control every prefs read via the mock.
    private val mockPrefs = mockk<SharedPreferences>(relaxed = true)
    private val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val mockContext = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor

        reporter = AbrpReporter(mockContext)
    }

    // ── Guard: blank token ────────────────────────────────────────────────────

    @Test
    fun `send skips when token is blank`() = runBlocking {
        // Token is blank (default mock returns null → "" via getOrEmpty)
        every { mockPrefs.getString("abrp_token", "") } returns ""

        // Build a data set with valid soc and Connected state
        val data = AutoVaktLiveData(
            soc = 80f,
            connectionState = ConnectionState.Connected
        )

        // Should return without attempting any network call (no exception thrown)
        reporter.send(data)
        // If we reach here without exception the send was skipped correctly.
    }

    // ── Guard: null soc ───────────────────────────────────────────────────────

    @Test
    fun `send skips when soc is null`() = runBlocking {
        every { mockPrefs.getString("abrp_token", "") } returns "some-valid-token"
        every { mockPrefs.getString("abrp_base_url", any()) } returns "https://api.iternio.com/1/tlm/send"

        val data = AutoVaktLiveData(
            soc = null,  // null → skip
            connectionState = ConnectionState.Connected
        )

        reporter.send(data)
        // Reaching here without exception confirms soc-null guard fired.
    }

    // ── Charging detection ────────────────────────────────────────────────────

    @Test
    fun `is_charging is 0 during regen braking — speed above 2 km-h with negative power`() {
        // 60 mph * 1.60934 = ~96.5 km/h → well above 2.0 km/h threshold
        // powerKw = -15.0 kW → negative (regen)
        // Expected: NOT charging (car is moving)
        val speedKmh = 96.5f
        val powerKw = -15.0f

        val result = reporter.isCharging(speedKmh, powerKw)

        assertEquals(
            "Regen braking at speed should NOT be reported as charging",
            0,
            result
        )
    }

    @Test
    fun `is_charging is 1 when stationary and power is negative charging`() {
        // speed = 0 km/h (parked), power = -7.2 kW (DC fast charging)
        val speedKmh = 0f
        val powerKw = -7.2f

        val result = reporter.isCharging(speedKmh, powerKw)

        assertEquals(
            "Stationary with negative power should be reported as charging",
            1,
            result
        )
    }

    @Test
    fun `is_charging is 0 when speed is below threshold but power is positive`() {
        // Stationary but drawing positive power (e.g. accessories, climate)
        val speedKmh = 0f
        val powerKw = 2.0f

        val result = reporter.isCharging(speedKmh, powerKw)

        assertEquals(
            "Positive power while stationary is not charging",
            0,
            result
        )
    }

    @Test
    fun `is_charging boundary — speed exactly 2 km-h is not charging`() {
        // Exactly 2.0 is NOT below 2.0, so charging = 0
        val speedKmh = 2.0f
        val powerKw = -10.0f

        val result = reporter.isCharging(speedKmh, powerKw)

        assertEquals(
            "Speed == 2.0 km/h should not trigger charging (threshold is strictly < 2.0)",
            0,
            result
        )
    }

    @Test
    fun `is_charging boundary — power exactly minus 0_5 is not charging`() {
        // Exactly -0.5 is NOT below -0.5, so charging = 0
        val speedKmh = 0f
        val powerKw = -0.5f

        val result = reporter.isCharging(speedKmh, powerKw)

        assertEquals(
            "Power == -0.5 kW should not trigger charging (threshold is strictly < -0.5)",
            0,
            result
        )
    }
}
