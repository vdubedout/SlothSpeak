package com.slothspeak.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** Whether HTTP tool calls with side effects (POST/PUT/PATCH/DELETE) need user confirmation. */
enum class HttpWritePolicy { ASK, ALWAYS_ALLOW }

/**
 * Immutable snapshot of Settings → Tools, built from [ToolConfigManager.snapshot].
 * The profiles carry their secret header values, which the UI must never render.
 */
data class ToolsSettingsState(
    val braveSearchEnabled: Boolean = false,
    val braveApiKeyConfigured: Boolean = false,
    val httpRequestEnabled: Boolean = false,
    val httpWritePolicy: HttpWritePolicy = HttpWritePolicy.ASK,
    val allowedWriteHosts: List<String> = emptyList(),
    /** Saved credentials; [HostAuthProfile.headerValue] is a secret and must never be rendered. */
    val hostAuthProfiles: List<HostAuthProfile> = emptyList()
)
/**
 * Per-host authentication header (e.g. `Authorization: Bearer ...`) attached by the HTTP
 * request plugin. [headerValue] is a secret and lives in EncryptedSharedPreferences.
 */
data class HostAuthProfile(
    val id: String,
    val host: String,
    val headerName: String,
    val headerValue: String
)

/**
 * Tool plugin configuration, following the CompatibleProviderManager pattern:
 * non-secret settings in plain [SharedPreferences], secrets in EncryptedSharedPreferences.
 *
 * - `configPrefs`: plugin toggles, HTTP write policy, allowed write hosts,
 *   host-auth profile metadata (id / host / headerName as a JSON array).
 * - `securePrefs`: Brave API key and host-auth header values (`host_auth_val_<id>`).
 */
class ToolConfigManager internal constructor(
    private val configPrefs: SharedPreferences,
    private val securePrefs: SharedPreferences
) {

    /** Production constructor: app prefs + EncryptedSharedPreferences for secrets. */
    constructor(context: Context) : this(
        configPrefs = context.getSharedPreferences(PREFS_CONFIG, Context.MODE_PRIVATE),
        securePrefs = createSecurePrefs(context)
    )

    /** Current settings for the Tools section of Settings. */
    fun snapshot(): ToolsSettingsState = ToolsSettingsState(
        braveSearchEnabled = isPluginEnabled(PLUGIN_BRAVE_SEARCH),
        braveApiKeyConfigured = hasBraveApiKey(),
        httpRequestEnabled = isPluginEnabled(PLUGIN_HTTP_REQUEST),
        httpWritePolicy = getHttpWritePolicy(),
        allowedWriteHosts = getAllowedWriteHosts().toList(),
        hostAuthProfiles = getHostAuthProfiles()
    )

    // --- Plugin toggles ---

    fun isPluginEnabled(pluginId: String): Boolean {
        return configPrefs.getBoolean(pluginEnabledKey(pluginId), false)
    }

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        configPrefs.edit().putBoolean(pluginEnabledKey(pluginId), enabled).apply()
    }

    // --- Brave Search API key ---

    fun getBraveApiKey(): String? {
        return securePrefs.getString(KEY_BRAVE_API_KEY, null)?.takeIf { it.isNotBlank() }
    }

    fun saveBraveApiKey(key: String) {
        securePrefs.edit().putString(KEY_BRAVE_API_KEY, key.trim()).apply()
    }

    fun deleteBraveApiKey() {
        securePrefs.edit().remove(KEY_BRAVE_API_KEY).apply()
    }

    fun hasBraveApiKey(): Boolean {
        return getBraveApiKey() != null
    }

    // --- HTTP write policy ---

    fun getHttpWritePolicy(): HttpWritePolicy {
        val stored = configPrefs.getString(KEY_HTTP_WRITE_POLICY, null)
        return HttpWritePolicy.entries.firstOrNull { it.name == stored } ?: HttpWritePolicy.ASK
    }

    fun setHttpWritePolicy(policy: HttpWritePolicy) {
        configPrefs.edit().putString(KEY_HTTP_WRITE_POLICY, policy.name).apply()
    }

    // --- Allowed write hosts ---

    fun getAllowedWriteHosts(): Set<String> {
        return configPrefs.getStringSet(KEY_ALLOWED_WRITE_HOSTS, null)
            ?.mapTo(LinkedHashSet()) { normalizeHost(it) }
            ?: emptySet()
    }

    fun addAllowedWriteHost(host: String) {
        val normalized = normalizeHost(host)
        if (normalized.isBlank()) return
        val hosts = getAllowedWriteHosts().toMutableSet()
        hosts.add(normalized)
        configPrefs.edit().putStringSet(KEY_ALLOWED_WRITE_HOSTS, hosts).apply()
    }

    fun removeAllowedWriteHost(host: String) {
        val hosts = getAllowedWriteHosts().toMutableSet()
        hosts.remove(normalizeHost(host))
        configPrefs.edit().putStringSet(KEY_ALLOWED_WRITE_HOSTS, hosts).apply()
    }

    fun clearAllowedWriteHosts() {
        configPrefs.edit().remove(KEY_ALLOWED_WRITE_HOSTS).apply()
    }

    // --- Host auth profiles ---

    fun getHostAuthProfiles(): List<HostAuthProfile> {
        val json = configPrefs.getString(KEY_HOST_AUTH_PROFILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                HostAuthProfile(
                    id = id,
                    host = obj.optString("host"),
                    headerName = obj.optString("headerName"),
                    headerValue = securePrefs.getString(hostAuthValueKey(id), null) ?: ""
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Profiles whose host equals [host] (case-insensitive exact match). */
    fun getHostAuthProfilesForHost(host: String): List<HostAuthProfile> {
        val target = normalizeHost(host)
        return getHostAuthProfiles().filter { normalizeHost(it.host) == target }
    }

    /** Inserts or replaces by [HostAuthProfile.id]; the header value goes to secure prefs. */
    fun saveHostAuthProfile(profile: HostAuthProfile) {
        val profiles = getHostAuthProfiles().filter { it.id != profile.id }.toMutableList()
        profiles.add(profile.copy(host = profile.host.trim(), headerName = profile.headerName.trim()))
        val arr = JSONArray().apply {
            profiles.forEach { put(it.toPublicJson()) }
        }
        configPrefs.edit().putString(KEY_HOST_AUTH_PROFILES, arr.toString()).apply()
        securePrefs.edit().putString(hostAuthValueKey(profile.id), profile.headerValue.trim()).apply()
    }

    fun deleteHostAuthProfile(id: String) {
        val profiles = getHostAuthProfiles().filter { it.id != id }
        val arr = JSONArray().apply {
            profiles.forEach { put(it.toPublicJson()) }
        }
        configPrefs.edit().putString(KEY_HOST_AUTH_PROFILES, arr.toString()).apply()
        securePrefs.edit().remove(hostAuthValueKey(id)).apply()
    }

    private fun HostAuthProfile.toPublicJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("host", host)
        .put("headerName", headerName)

    companion object {
        const val PLUGIN_BRAVE_SEARCH = "brave_search"
        const val PLUGIN_HTTP_REQUEST = "http_request"

        private const val PREFS_CONFIG = "tool_config_prefs"
        private const val PREFS_SECURE = "tool_config_secure_prefs"

        private const val KEY_PLUGIN_ENABLED_PREFIX = "plugin_enabled_"
        private const val KEY_BRAVE_API_KEY = "brave_api_key"
        private const val KEY_HTTP_WRITE_POLICY = "http_write_policy"
        private const val KEY_ALLOWED_WRITE_HOSTS = "http_allowed_write_hosts"
        private const val KEY_HOST_AUTH_PROFILES = "host_auth_profiles"
        private const val KEY_HOST_AUTH_VALUE_PREFIX = "host_auth_val_"

        private fun pluginEnabledKey(pluginId: String): String = "$KEY_PLUGIN_ENABLED_PREFIX$pluginId"

        private fun hostAuthValueKey(id: String): String = "$KEY_HOST_AUTH_VALUE_PREFIX$id"

        /** Hosts are compared/stored lowercase and trimmed. */
        private fun normalizeHost(host: String): String = host.trim().lowercase()

        private fun createSecurePrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                PREFS_SECURE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}
