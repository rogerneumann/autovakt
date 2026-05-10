package com.rogerneumann.vakt.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VehicleProfileHubTest {

    @Test
    fun testImportProfileFromJson_validProfile() {
        val context = mockk<Context>(relaxed = true)
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_profiles_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        every { context.filesDir } returns tempDir
        every { context.assets.list("profiles") } returns arrayOf()

        val hub = VehicleProfileHub(context)

        val validJson = JSONObject().apply {
            put("id", "test_profile_001")
            put("make", "Tesla")
            put("model", "Model 3")
            put("year", 2023)
            put("powertrain", "EV")
            put("pids", JSONArray().put(
                JSONObject().apply {
                    put("name", "Battery SOC")
                    put("shortName", "SOC")
                    put("modeAndPid", "2101")
                    put("equation", "A/2.55")
                    put("units", "%")
                }
            ))
        }.toString()

        hub.importProfileFromJson(validJson)

        val profiles = hub.getAvailableProfiles()
        assertEquals(1, profiles.size)
        assertEquals("test_profile_001", profiles[0].id)
        assertEquals("Tesla", profiles[0].make)

        // Verify file was saved
        val profileFile = File(tempDir, "profiles/test_profile_001.json")
        assert(profileFile.exists())

        tempDir.deleteRecursively()
    }

    @Test
    fun testImportProfileFromJson_missingId() {
        val context = mockk<Context>(relaxed = true)
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_profiles_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        every { context.filesDir } returns tempDir
        every { context.assets.list("profiles") } returns arrayOf()

        val hub = VehicleProfileHub(context)

        val invalidJson = JSONObject().apply {
            put("make", "Tesla")
            put("pids", JSONArray())
        }.toString()

        assertFailsWith<IllegalArgumentException> {
            hub.importProfileFromJson(invalidJson)
        }

        tempDir.deleteRecursively()
    }

    @Test
    fun testImportProfileFromJson_missingMake() {
        val context = mockk<Context>(relaxed = true)
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_profiles_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        every { context.filesDir } returns tempDir
        every { context.assets.list("profiles") } returns arrayOf()

        val hub = VehicleProfileHub(context)

        val invalidJson = JSONObject().apply {
            put("id", "test_profile")
            put("pids", JSONArray())
        }.toString()

        assertFailsWith<IllegalArgumentException> {
            hub.importProfileFromJson(invalidJson)
        }

        tempDir.deleteRecursively()
    }

    @Test
    fun testImportProfileFromJson_noPids() {
        val context = mockk<Context>(relaxed = true)
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_profiles_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        every { context.filesDir } returns tempDir
        every { context.assets.list("profiles") } returns arrayOf()

        val hub = VehicleProfileHub(context)

        val invalidJson = JSONObject().apply {
            put("id", "test_profile")
            put("make", "Tesla")
            put("pids", JSONArray())
        }.toString()

        assertFailsWith<IllegalArgumentException> {
            hub.importProfileFromJson(invalidJson)
        }

        tempDir.deleteRecursively()
    }
}
