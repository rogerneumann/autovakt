package com.rogerneumann.autovakt.auto

import androidx.car.app.model.ListTemplate
import androidx.car.app.model.TabTemplate
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import com.rogerneumann.autovakt.auto.screens.DashboardScreen
import com.rogerneumann.autovakt.data.AutoVaktLiveData
import com.rogerneumann.autovakt.data.OBD2Repository
import com.rogerneumann.autovakt.data.TripRepository
import com.rogerneumann.autovakt.data.VehicleLayoutManager
import com.rogerneumann.autovakt.media.MediaRemoteManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [DashboardScreen.onGetTemplate].
 *
 * Robolectric is required because [TestCarContext.createCarContext] calls
 * [android.content.ContextWrapper.attachBaseContext], and the high-API-level
 * path in [DashboardScreen.onGetTemplate] calls
 * [IconCompat.createWithResource] which resolves drawable resources from the
 * real app context.
 *
 * API level control:
 *   [TestCarContext] negotiates [carAppApiLevel] internally against its own
 *   [FakeHost] and exposes no public setter. We force the backing field
 *   [mCarAppApiLevel] (declared in [androidx.car.app.CarContext]) via
 *   reflection — the only reliable approach without a public API for this.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DashboardScreenTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun makeTestCarContext(): TestCarContext =
        TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())

    /**
     * Forces the negotiated API level on a [TestCarContext] by writing
     * directly to the [mCarAppApiLevel] field declared on
     * [androidx.car.app.CarContext] or one of its ancestors.
     */
    private fun TestCarContext.forceApiLevel(level: Int) {
        var cls: Class<*>? = this::class.java
        while (cls != null) {
            try {
                val field = cls.getDeclaredField("mCarAppApiLevel")
                field.isAccessible = true
                field.set(this, level)
                return
            } catch (_: NoSuchFieldException) {}
            cls = cls.superclass
        }
        error("mCarAppApiLevel not found on ${this::class.java} or its superclasses")
    }

    private fun makeRepository(): OBD2Repository = mockk<OBD2Repository>(relaxed = true).also {
        every { it.liveData } returns MutableStateFlow(AutoVaktLiveData())
        every { it.currentLayoutKey } returns MutableStateFlow("gauge_layout_global")
    }

    private fun makeMediaManager(): MediaRemoteManager =
        mockk<MediaRemoteManager>(relaxed = true).also {
            every { it.currentMetadata } returns MutableStateFlow("" to "")
        }

    private fun makeTripRepository(): TripRepository = mockk<TripRepository>(relaxed = true).also {
        every { it.getAllTrips() } returns MutableStateFlow(emptyList())
    }

    private fun makeVehicleLayoutManager(): VehicleLayoutManager = mockk<VehicleLayoutManager>(relaxed = true)

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * API level >= 6: [DashboardScreen.onGetTemplate] must return a
     * [TabTemplate] with exactly 4 tabs whose content IDs are
     * "gauges", "media", "trip", and "data".
     */
    @Test
    fun `high api level returns TabTemplate with gauges media trip tabs`() {
        val carContext = makeTestCarContext().also { it.forceApiLevel(6) }

        val screen = DashboardScreen(carContext, makeRepository(), makeMediaManager(), makeTripRepository(), makeVehicleLayoutManager())
        val template = screen.onGetTemplate()

        assertTrue(
            "Expected TabTemplate but got ${template::class.simpleName}",
            template is TabTemplate
        )

        val tabs = (template as TabTemplate).tabs
        assertEquals("Expected 4 tabs", 4, tabs.size)

        val ids = tabs.map { it.contentId }.toSet()
        assertEquals(setOf("gauges", "media", "trip", "data"), ids)
    }

    /**
     * API level < 6: [DashboardScreen.onGetTemplate] must return a
     * [ListTemplate] (single-screen gauges fallback).
     */
    @Test
    fun `low api level returns ListTemplate`() {
        val carContext = makeTestCarContext().also { it.forceApiLevel(5) }

        val screen = DashboardScreen(carContext, makeRepository(), makeMediaManager(), makeTripRepository(), makeVehicleLayoutManager())
        val template = screen.onGetTemplate()

        assertTrue(
            "Expected ListTemplate for API level < 6 but got ${template::class.simpleName}",
            template is ListTemplate
        )
    }

    /**
     * Smoke test: [onGetTemplate] does not throw at API level 6.
     */
    @Test
    fun `onGetTemplate does not throw`() {
        val carContext = makeTestCarContext().also { it.forceApiLevel(6) }
        val screen = DashboardScreen(carContext, makeRepository(), makeMediaManager(), makeTripRepository(), makeVehicleLayoutManager())
        assertNotNull(screen.onGetTemplate())
    }
}
