package com.slothspeak.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * CRUD tests for ToolConfigManager using the plain-prefs constructor (no Android Keystore).
 * Runs under Robolectric for real SharedPreferences + org.json.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ToolConfigManagerTest {

    private lateinit var context: Context
    private lateinit var configPrefs: SharedPreferences
    private lateinit var securePrefs: SharedPreferences
    private lateinit var manager: ToolConfigManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        configPrefs = context.getSharedPreferences("test_tool_config", Context.MODE_PRIVATE)
        securePrefs = context.getSharedPreferences("test_tool_secure", Context.MODE_PRIVATE)
        configPrefs.edit().clear().commit()
        securePrefs.edit().clear().commit()
        manager = ToolConfigManager(configPrefs, securePrefs)
    }

    @After
    fun tearDown() {
        configPrefs.edit().clear().commit()
        securePrefs.edit().clear().commit()
    }

    // --- Plugin toggles ---

    @Test
    fun pluginEnabled_defaultsToFalse() {
        assertFalse(manager.isPluginEnabled(ToolConfigManager.PLUGIN_BRAVE_SEARCH))
        assertFalse(manager.isPluginEnabled(ToolConfigManager.PLUGIN_HTTP_REQUEST))
        assertFalse(manager.isPluginEnabled("unknown_plugin"))
    }

    @Test
    fun pluginEnabled_roundTrip() {
        manager.setPluginEnabled(ToolConfigManager.PLUGIN_BRAVE_SEARCH, true)

        assertTrue(manager.isPluginEnabled(ToolConfigManager.PLUGIN_BRAVE_SEARCH))
        assertFalse(manager.isPluginEnabled(ToolConfigManager.PLUGIN_HTTP_REQUEST))

        manager.setPluginEnabled(ToolConfigManager.PLUGIN_BRAVE_SEARCH, false)

        assertFalse(manager.isPluginEnabled(ToolConfigManager.PLUGIN_BRAVE_SEARCH))
    }

    @Test
    fun pluginToggles_areIndependentPerPlugin() {
        manager.setPluginEnabled(ToolConfigManager.PLUGIN_HTTP_REQUEST, true)

        assertTrue(manager.isPluginEnabled(ToolConfigManager.PLUGIN_HTTP_REQUEST))
        assertFalse(manager.isPluginEnabled(ToolConfigManager.PLUGIN_BRAVE_SEARCH))
    }

    @Test
    fun pluginToggle_isStoredInConfigPrefsNotSecurePrefs() {
        manager.setPluginEnabled(ToolConfigManager.PLUGIN_BRAVE_SEARCH, true)

        assertTrue(configPrefs.getBoolean("plugin_enabled_brave_search", false))
        assertTrue(securePrefs.all.isEmpty())
    }

    // --- Brave API key ---

    @Test
    fun braveApiKey_crud() {
        assertFalse(manager.hasBraveApiKey())
        assertNull(manager.getBraveApiKey())

        manager.saveBraveApiKey("  BSA-secret-key-123  ")

        assertTrue(manager.hasBraveApiKey())
        assertEquals("BSA-secret-key-123", manager.getBraveApiKey())

        manager.deleteBraveApiKey()

        assertFalse(manager.hasBraveApiKey())
        assertNull(manager.getBraveApiKey())
    }

    @Test
    fun braveApiKey_isOnlyEverStoredInSecurePrefs() {
        manager.saveBraveApiKey("BSA-secret-key-123")

        assertEquals("BSA-secret-key-123", securePrefs.getString("brave_api_key", null))
        assertFalse("secret leaked into config prefs", configPrefs.all.values.any { it.toString().contains("BSA-secret-key-123") })
    }

    @Test
    fun braveApiKey_overwriteReplacesPreviousValue() {
        manager.saveBraveApiKey("first")
        manager.saveBraveApiKey("second")

        assertEquals("second", manager.getBraveApiKey())
    }

    // --- Brave request spacing ---

    @Test
    fun braveMinIntervalMs_defaultsTo1000() {
        assertEquals(ToolConfigManager.DEFAULT_BRAVE_MIN_INTERVAL_MS, manager.getBraveMinIntervalMs())
        assertEquals(1000, manager.getBraveMinIntervalMs())
    }

    @Test
    fun braveMinIntervalMs_roundTrip() {
        manager.setBraveMinIntervalMs(2500)

        assertEquals(2500, manager.getBraveMinIntervalMs())
        assertEquals(2500, configPrefs.getInt("brave_min_interval_ms", -1))
    }

    @Test
    fun braveMinIntervalMs_isClampedTo0Through10000() {
        manager.setBraveMinIntervalMs(-500)
        assertEquals(0, manager.getBraveMinIntervalMs())

        manager.setBraveMinIntervalMs(999_999)
        assertEquals(10000, manager.getBraveMinIntervalMs())
    }

    @Test
    fun braveMinIntervalMs_storedOutOfRangeValueIsClampedOnRead() {
        configPrefs.edit().putInt("brave_min_interval_ms", 42_000).commit()

        assertEquals(10000, manager.getBraveMinIntervalMs())
    }

    @Test
    fun snapshot_includesBraveMinIntervalMs() {
        manager.setBraveMinIntervalMs(1500)

        val snapshot = manager.snapshot()

        assertEquals(1500, snapshot.braveMinIntervalMs)
        // Defaults for an untouched snapshot
        assertEquals(
            ToolConfigManager.DEFAULT_BRAVE_MIN_INTERVAL_MS,
            ToolsSettingsState().braveMinIntervalMs
        )
    }

    // --- HTTP write policy ---

    @Test
    fun httpWritePolicy_defaultsToAsk() {
        assertEquals(HttpWritePolicy.ASK, manager.getHttpWritePolicy())
    }

    @Test
    fun httpWritePolicy_roundTrip() {
        manager.setHttpWritePolicy(HttpWritePolicy.ALWAYS_ALLOW)
        assertEquals(HttpWritePolicy.ALWAYS_ALLOW, manager.getHttpWritePolicy())

        manager.setHttpWritePolicy(HttpWritePolicy.ASK)
        assertEquals(HttpWritePolicy.ASK, manager.getHttpWritePolicy())
    }

    @Test
    fun httpWritePolicy_unknownStoredValueFallsBackToAsk() {
        configPrefs.edit().putString("http_write_policy", "YOLO").commit()

        assertEquals(HttpWritePolicy.ASK, manager.getHttpWritePolicy())
    }

    // --- Allowed write hosts ---

    @Test
    fun allowedWriteHosts_defaultEmpty() {
        assertTrue(manager.getAllowedWriteHosts().isEmpty())
    }

    @Test
    fun allowedWriteHosts_normalizesToLowercaseTrimmed() {
        manager.addAllowedWriteHost("  API.Example.COM ")

        assertEquals(setOf("api.example.com"), manager.getAllowedWriteHosts())
    }

    @Test
    fun allowedWriteHosts_addRemoveClear() {
        manager.addAllowedWriteHost("api.example.com")
        manager.addAllowedWriteHost("Localhost:8000")

        assertEquals(setOf("api.example.com", "localhost:8000"), manager.getAllowedWriteHosts())

        manager.removeAllowedWriteHost("API.EXAMPLE.COM")

        assertEquals(setOf("localhost:8000"), manager.getAllowedWriteHosts())

        manager.clearAllowedWriteHosts()

        assertTrue(manager.getAllowedWriteHosts().isEmpty())
    }

    @Test
    fun allowedWriteHosts_caseVariantsDoNotDuplicate() {
        manager.addAllowedWriteHost("api.example.com")
        manager.addAllowedWriteHost("API.example.com")

        assertEquals(setOf("api.example.com"), manager.getAllowedWriteHosts())
    }

    @Test
    fun allowedWriteHosts_removingUnknownHostIsANoOp() {
        manager.addAllowedWriteHost("api.example.com")

        manager.removeAllowedWriteHost("nope.example.com")

        assertEquals(setOf("api.example.com"), manager.getAllowedWriteHosts())
    }

    @Test
    fun allowedWriteHosts_blankHostIsIgnored() {
        manager.addAllowedWriteHost("   ")

        assertTrue(manager.getAllowedWriteHosts().isEmpty())
    }

    // --- Host auth profiles ---

    @Test
    fun hostAuthProfiles_defaultEmpty() {
        assertTrue(manager.getHostAuthProfiles().isEmpty())
        assertTrue(manager.getHostAuthProfilesForHost("api.example.com").isEmpty())
    }

    @Test
    fun hostAuthProfiles_saveAndList() {
        manager.saveHostAuthProfile(
            HostAuthProfile("p1", "api.example.com", "Authorization", "Bearer token-one")
        )
        manager.saveHostAuthProfile(
            HostAuthProfile("p2", "other.example.org", "X-Api-Key", "key-two")
        )

        val profiles = manager.getHostAuthProfiles()

        assertEquals(2, profiles.size)
        val first = profiles.first { it.id == "p1" }
        assertEquals("api.example.com", first.host)
        assertEquals("Authorization", first.headerName)
        assertEquals("Bearer token-one", first.headerValue)
        assertEquals("key-two", profiles.first { it.id == "p2" }.headerValue)
    }

    @Test
    fun hostAuthProfiles_forHost_isCaseInsensitiveExactMatch() {
        manager.saveHostAuthProfile(HostAuthProfile("p1", "api.example.com", "Authorization", "Bearer one"))
        manager.saveHostAuthProfile(HostAuthProfile("p2", "API.EXAMPLE.COM", "X-Api-Key", "two"))
        manager.saveHostAuthProfile(HostAuthProfile("p3", "sub.api.example.com", "Authorization", "Bearer three"))
        manager.saveHostAuthProfile(HostAuthProfile("p4", "other.example.com", "Authorization", "Bearer four"))

        val matches = manager.getHostAuthProfilesForHost("Api.Example.Com")

        assertEquals(listOf("p1", "p2"), matches.map { it.id })
        assertEquals("Bearer one", matches[0].headerValue)
        assertTrue(manager.getHostAuthProfilesForHost("example.com").isEmpty())
        assertTrue(manager.getHostAuthProfilesForHost("unknown.host").isEmpty())
    }

    @Test
    fun hostAuthProfiles_saveWithSameIdUpdatesInPlace() {
        manager.saveHostAuthProfile(HostAuthProfile("p1", "api.example.com", "Authorization", "Bearer old"))
        manager.saveHostAuthProfile(HostAuthProfile("p2", "other.example.com", "X-Api-Key", "other"))

        manager.saveHostAuthProfile(HostAuthProfile("p1", "new.example.com", "X-Api-Key", "Bearer new"))

        val profiles = manager.getHostAuthProfiles()
        assertEquals(2, profiles.size)
        val updated = profiles.single { it.id == "p1" }
        assertEquals("new.example.com", updated.host)
        assertEquals("X-Api-Key", updated.headerName)
        assertEquals("Bearer new", updated.headerValue)
        assertEquals("Bearer new", securePrefs.getString("host_auth_val_p1", null))
    }

    @Test
    fun hostAuthProfiles_deleteRemovesMetadataAndSecret() {
        manager.saveHostAuthProfile(HostAuthProfile("p1", "api.example.com", "Authorization", "Bearer one"))
        manager.saveHostAuthProfile(HostAuthProfile("p2", "other.example.com", "Authorization", "Bearer two"))

        manager.deleteHostAuthProfile("p1")

        assertEquals(listOf("p2"), manager.getHostAuthProfiles().map { it.id })
        assertFalse(securePrefs.contains("host_auth_val_p1"))
        assertTrue(securePrefs.contains("host_auth_val_p2"))

        manager.deleteHostAuthProfile("does-not-exist")

        assertEquals(listOf("p2"), manager.getHostAuthProfiles().map { it.id })
    }

    @Test
    fun hostAuthProfiles_headerValueNeverStoredInConfigPrefs() {
        manager.saveHostAuthProfile(HostAuthProfile("p1", "api.example.com", "Authorization", "Bearer secret-value"))

        val stored = configPrefs.getString("host_auth_profiles", null)
        assertTrue(stored != null)
        assertFalse(stored!!.contains("secret-value"))
        assertFalse(stored.contains("headerValue"))

        val arr = JSONArray(stored)
        assertEquals(1, arr.length())
        val obj = arr.getJSONObject(0)
        assertEquals("p1", obj.getString("id"))
        assertEquals("api.example.com", obj.getString("host"))
        assertEquals("Authorization", obj.getString("headerName"))
        assertFalse(obj.has("headerValue"))

        assertEquals("Bearer secret-value", securePrefs.getString("host_auth_val_p1", null))
    }

    @Test
    fun hostAuthProfiles_corruptJsonYieldsEmptyList() {
        configPrefs.edit().putString("host_auth_profiles", "{not json").commit()

        assertTrue(manager.getHostAuthProfiles().isEmpty())
    }

    @Test
    fun hostAuthProfiles_valuesAreTrimmedOnSave() {
        manager.saveHostAuthProfile(HostAuthProfile("p1", " api.example.com ", " X-Api-Key ", "  token  "))

        val profile = manager.getHostAuthProfilesForHost("api.example.com").single()
        assertEquals("api.example.com", profile.host)
        assertEquals("X-Api-Key", profile.headerName)
        assertEquals("token", profile.headerValue)
    }

    // --- Cross-cutting ---

    @Test
    fun constants_areStable() {
        assertEquals("brave_search", ToolConfigManager.PLUGIN_BRAVE_SEARCH)
        assertEquals("http_request", ToolConfigManager.PLUGIN_HTTP_REQUEST)
    }
}
