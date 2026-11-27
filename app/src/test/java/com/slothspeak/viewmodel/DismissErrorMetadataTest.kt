package com.slothspeak.viewmodel

import com.slothspeak.service.SlothSpeakService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DismissErrorMetadataTest {

    @Test
    fun applyDismissedTtsErrorMetadata_linksQaPairResponseAndModelOnMatchingItem() {
        val prior = ConversationViewModel.SessionItem(
            questionText = "First question",
            answerText = "First answer",
            responseId = "resp_1",
            audioFiles = null,
            model = "gpt-5.5",
            qaPairId = 10L
        )
        val failedTtsItem = ConversationViewModel.SessionItem(
            questionText = "Second question",
            answerText = "Second answer",
            responseId = null,
            audioFiles = null,
            isProcessing = false,
            model = null,
            qaPairId = null
        )
        val errorState = SlothSpeakService.PipelineState.Error(
            message = "On-device TTS request rejected (code=-1, chars=4050, engineLimit=4000)",
            failedStep = SlothSpeakService.FailedStep.TTS,
            questionText = "Second question",
            answerText = "Second answer",
            responseId = "resp_2",
            conversationId = 7L,
            qaPairId = 11L,
            model = "claude-opus-4-8"
        )

        val patched = ConversationViewModel.applyDismissedTtsErrorMetadata(
            items = listOf(prior, failedTtsItem),
            errorState = errorState
        )

        assertEquals(2, patched.size)
        assertEquals(10L, patched[0].qaPairId)
        assertEquals("gpt-5.5", patched[0].model)
        assertEquals(11L, patched[1].qaPairId)
        assertEquals("resp_2", patched[1].responseId)
        assertEquals("claude-opus-4-8", patched[1].model)
        assertEquals("Second answer", patched[1].answerText)
    }

    @Test
    fun applyDismissedTtsErrorMetadata_emptyList_isSafeNoOp() {
        val errorState = SlothSpeakService.PipelineState.Error(
            message = "TTS error",
            failedStep = SlothSpeakService.FailedStep.TTS,
            questionText = "Question",
            answerText = "Answer",
            responseId = "resp_1",
            conversationId = 1L,
            qaPairId = 2L,
            model = "gpt-5.5"
        )

        val patched = ConversationViewModel.applyDismissedTtsErrorMetadata(emptyList(), errorState)
        assertEquals(0, patched.size)
        assertNull(
            ConversationViewModel.applyDismissedTtsErrorMetadata(
                listOf(
                    ConversationViewModel.SessionItem(
                        questionText = "Q",
                        answerText = "A",
                        responseId = null,
                        audioFiles = null
                    )
                ),
                errorState.copy(qaPairId = null, responseId = "", model = null)
            ).single().qaPairId
        )
    }

    @Test
    fun resolveTtsRetryTarget_routesReadAloudAndPipelineErrorsCorrectly() {
        val readAloudFlagged = SlothSpeakService.PipelineState.Error(
            message = "Custom TTS server error",
            failedStep = SlothSpeakService.FailedStep.TTS,
            questionText = "Q",
            answerText = "A",
            responseId = "resp_1",
            qaPairId = null,
            model = "gpt-5.5",
            fromReadAloud = true
        )
        assertEquals(
            SlothSpeakService.Companion.TtsRetryTarget.READ_ALOUD,
            SlothSpeakService.resolveTtsRetryTarget(readAloudFlagged, hasEarlyPipelinePair = false)
        )

        val readAloudWithoutEarlyPair = readAloudFlagged.copy(fromReadAloud = false, qaPairId = 42L)
        assertEquals(
            SlothSpeakService.Companion.TtsRetryTarget.READ_ALOUD,
            SlothSpeakService.resolveTtsRetryTarget(readAloudWithoutEarlyPair, hasEarlyPipelinePair = false)
        )

        val pipelineTtsError = readAloudFlagged.copy(fromReadAloud = false, qaPairId = 42L)
        assertEquals(
            SlothSpeakService.Companion.TtsRetryTarget.PIPELINE,
            SlothSpeakService.resolveTtsRetryTarget(pipelineTtsError, hasEarlyPipelinePair = true)
        )

        val llmError = pipelineTtsError.copy(failedStep = SlothSpeakService.FailedStep.LLM)
        assertEquals(
            SlothSpeakService.Companion.TtsRetryTarget.NONE,
            SlothSpeakService.resolveTtsRetryTarget(llmError, hasEarlyPipelinePair = true)
        )
    }
}
