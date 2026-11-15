package com.slothspeak.api

import com.slothspeak.api.models.ChatCompletionRequest
import com.slothspeak.api.models.ChatCompletionResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ChatCompletionsService {

    @POST("chat/completions")
    suspend fun createChatCompletion(
        @Body request: ChatCompletionRequest
    ): Response<ChatCompletionResponse>
}