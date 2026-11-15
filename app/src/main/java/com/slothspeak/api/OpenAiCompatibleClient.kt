package com.slothspeak.api

import com.slothspeak.api.models.ChatCompletionRequest
import com.slothspeak.api.models.ChatRequestMessage
import com.slothspeak.api.models.ThinkingConfig
import com.slothspeak.data.CompatibleProviderConfig
import com.slothspeak.data.CompatibleProviderManager
import com.squareup.moshi.Moshi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class CompatibleLlmResult(
    val answerText: String,
    val reasoningText: String?,
    val responseId: String
)

class OpenAiCompatibleClient(
    private val compatibleProviderManager: CompatibleProviderManager
) {

    private val moshi = Moshi.Builder().build()
    private val httpClientCache = mutableMapOf<String, OkHttpClient>()
    private val serviceCache = mutableMapOf<String, ChatCompletionsService>()

    private fun getOrCreateHttpClient(baseUrl: String, apiKey: String?): OkHttpClient {
        val cacheKey = "${baseUrl}_${apiKey ?: ""}"
        return httpClientCache.getOrPut(cacheKey) {
            val authInterceptor = Interceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                if (!apiKey.isNullOrBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                }
                chain.proceed(requestBuilder.build())
            }

            OkHttpClient.Builder()
                .addInterceptor(authInterceptor)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(1500, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    suspend fun createResponse(
        question: String,
        history: List<Pair<String, String>>,
        provider: CompatibleProviderConfig,
        systemPrompt: String
    ): CompatibleLlmResult {
        val baseUrl = normalizeBaseUrl(provider.baseUrl)
        val apiKey = compatibleProviderManager.getProviderKey(provider.id)

        val httpClient = getOrCreateHttpClient(baseUrl, apiKey)

        val cacheKey = "${baseUrl}_${apiKey ?: ""}"
        val service: ChatCompletionsService = try {
            serviceCache.getOrPut(cacheKey) {
                Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(httpClient)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
                    .create(ChatCompletionsService::class.java)
            }
        } catch (e: IllegalArgumentException) {
            throw ApiException(
                "Invalid base URL for ${provider.displayName}. Please check the URL in Settings.",
                0,
                false
            )
        }

        // Build messages: system prompt first, then history pairs, then current question
        val messages = mutableListOf<ChatRequestMessage>()
        messages.add(ChatRequestMessage(role = "system", content = systemPrompt))

        for ((prevQuestion, prevAnswer) in history) {
            messages.add(ChatRequestMessage(role = "user", content = prevQuestion))
            messages.add(ChatRequestMessage(role = "assistant", content = prevAnswer))
        }

        messages.add(ChatRequestMessage(role = "user", content = question))

        // Build thinking config
        val thinking: ThinkingConfig? = if (provider.sendThinkingParams) {
            if (provider.thinkingEnabled) {
                ThinkingConfig(type = "enabled")
            } else {
                ThinkingConfig(type = "disabled")
            }
        } else {
            null
        }

        val reasoningEffort: String? = if (provider.sendThinkingParams && provider.thinkingEnabled) {
            provider.reasoningEffort
        } else {
            null
        }

        val request = ChatCompletionRequest(
            model = provider.modelId,
            messages = messages,
            thinking = thinking,
            reasoningEffort = reasoningEffort,
            maxTokens = provider.maxTokens,
            stream = null  // never sent
        )

        val response = service.createChatCompletion(request)
        if (response.isSuccessful) {
            val body = response.body() ?: throw ApiException(
                "Empty response from ${provider.displayName}",
                0,
                false
            )
            val choice = body.choices.firstOrNull()
                ?: throw ApiException(
                    "No choices in ${provider.displayName} response",
                    0,
                    true
                )
            val answerText = choice.message.content?.takeIf { it.isNotBlank() }
                ?: throw ApiException(
                    "No text content found in ${provider.displayName} response",
                    0,
                    true
                )
            val reasoningText = choice.message.reasoningContent?.takeIf { it.isNotBlank() }
            val respId = body.id.ifBlank { "${provider.id}-${Random.nextLong()}" }

            return CompatibleLlmResult(
                answerText = answerText,
                reasoningText = reasoningText,
                responseId = respId
            )
        } else {
            val errorBody = response.errorBody()?.string()
            throw parseApiError(response.code(), errorBody, provider.displayName)
        }
    }

    private fun parseApiError(code: Int, errorBody: String?, displayName: String): ApiException {
        return when (code) {
            400 -> ApiException(
                "Bad request to $displayName. Please check your configuration.",
                code,
                false
            )
            401, 403 -> ApiException(
                "Invalid $displayName API key. Please check your key in Settings.",
                code,
                true
            )
            429 -> ApiException(
                "Rate limited by $displayName. Please wait and try again.",
                code,
                true
            )
            in 500..599 -> ApiException(
                "$displayName server error. Please try again later.",
                code,
                true
            )
            else -> ApiException(
                "$displayName API request failed (HTTP $code)",
                code,
                true
            )
        }
    }

    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) throw ApiException("Base URL is blank for compatible provider", 0, false)
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}
