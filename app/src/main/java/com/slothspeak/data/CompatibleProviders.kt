package com.slothspeak.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class CompatibleProviderConfig(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val modelId: String,
    val thinkingEnabled: Boolean = true,
    val reasoningEffort: String = "high",
    val sendThinkingParams: Boolean = true,
    val maxTokens: Int? = null
) {
    companion object {
        fun createZai(id: String): CompatibleProviderConfig = CompatibleProviderConfig(
            id = id,
            displayName = "Z.ai",
            baseUrl = "https://api.z.ai/api/paas/v4/",
            modelId = "glm-5.3",
            thinkingEnabled = true,
            reasoningEffort = "high",
            sendThinkingParams = true,
            maxTokens = null
        )

        fun createDeepSeek(id: String): CompatibleProviderConfig = CompatibleProviderConfig(
            id = id,
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1/",
            modelId = "deepseek-v4-flash",
            thinkingEnabled = true,
            reasoningEffort = "high",
            sendThinkingParams = true,
            maxTokens = 8192
        )

        fun createCustom(id: String): CompatibleProviderConfig = CompatibleProviderConfig(
            id = id,
            displayName = "Custom",
            baseUrl = "",
            modelId = "",
            thinkingEnabled = true,
            reasoningEffort = "high",
            sendThinkingParams = false,
            maxTokens = null
        )
    }
}

class CompatibleProviderManager(context: Context) {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val listType = Types.newParameterizedType(
        MutableList::class.java,
        CompatibleProviderConfig::class.java
    )
    private val listAdapter: JsonAdapter<List<CompatibleProviderConfig>> =
        moshi.adapter(listType)

    private val configPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_CONFIG, Context.MODE_PRIVATE)

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_SECURE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // --- Config CRUD ---

    fun getProviders(): List<CompatibleProviderConfig> {
        val json = configPrefs.getString(KEY_PROVIDERS, null) ?: return emptyList()
        return try {
            listAdapter.fromJson(json) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveProvider(config: CompatibleProviderConfig) {
        val providers = getProviders().toMutableList()
        val existingIndex = providers.indexOfFirst { it.id == config.id }
        if (existingIndex >= 0) {
            providers[existingIndex] = config
        } else {
            providers.add(config)
        }
        val json = listAdapter.toJson(providers)
        configPrefs.edit().putString(KEY_PROVIDERS, json).apply()
    }

    fun deleteProvider(id: String) {
        val providers = getProviders().toMutableList()
        providers.removeAll { it.id == id }
        val json = listAdapter.toJson(providers)
        configPrefs.edit().putString(KEY_PROVIDERS, json).apply()
        deleteProviderKey(id)
    }

    fun getProvider(id: String): CompatibleProviderConfig? {
        return getProviders().firstOrNull { it.id == id }
    }

    // --- API Key CRUD ---

    fun saveProviderKey(id: String, key: String) {
        securePrefs.edit().putString("compat_key_$id", key.trim()).apply()
    }

    fun getProviderKey(id: String): String? {
        return securePrefs.getString("compat_key_$id", null)?.takeIf { it.isNotBlank() }
    }

    fun deleteProviderKey(id: String) {
        securePrefs.edit().remove("compat_key_$id").apply()
    }

    companion object {
        private const val PREFS_CONFIG = "compat_providers_config"
        private const val PREFS_SECURE = "compat_providers_secure"
        private const val KEY_PROVIDERS = "providers"
    }
}