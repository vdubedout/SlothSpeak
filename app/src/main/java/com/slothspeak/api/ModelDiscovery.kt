package com.slothspeak.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Discovers model IDs from an OpenAI-compatible `/models` endpoint.
 *
 * This is an optional convenience for the provider dialog: a failed request
 * never blocks saving a provider, the user can still type the model ID manually.
 */
object ModelDiscovery {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetchModels(baseUrl: String, apiKey: String?): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = baseUrl.trim().trimEnd('/')
                require(trimmed.isNotEmpty()) { "Base URL is required" }
                val request = Request.Builder()
                    .url("$trimmed/models")
                    .get()
                    .apply {
                        if (!apiKey.isNullOrBlank()) {
                            header("Authorization", "Bearer ${apiKey.trim()}")
                        }
                    }
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}")
                    }
                    parseModelIds(response.body?.string().orEmpty())
                }
            }
        }

    /**
     * Accepts the common shapes returned by compatible gateways:
     * `{"data":[{"id":"..."}]}`, `{"models":[{"id":"..."}]}`, or a bare array.
     */
    internal fun parseModelIds(body: String): List<String> {
        if (body.isBlank()) return emptyList()

        val ids = mutableListOf<String>()
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) {
            collectFromArray(JSONArray(trimmed), ids)
        } else {
            val root = JSONObject(trimmed)
            val array = root.optJSONArray("data") ?: root.optJSONArray("models")
            if (array != null) {
                collectFromArray(array, ids)
            }
        }
        return ids.distinct().sorted()
    }

    private fun collectFromArray(array: JSONArray, out: MutableList<String>) {
        for (i in 0 until array.length()) {
            val item = array.opt(i)
            val id = when (item) {
                is JSONObject -> item.optString("id").takeIf { it.isNotBlank() }
                    ?: item.optString("name").takeIf { it.isNotBlank() }
                    ?: item.optString("model").takeIf { it.isNotBlank() }
                is String -> item.takeIf { it.isNotBlank() }
                else -> null
            }
            if (id != null) out += id
        }
    }
}
