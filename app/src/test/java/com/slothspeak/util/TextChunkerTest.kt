package com.slothspeak.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    @Test
    fun chunkText_withinDefaultLimit_returnsSingleChunk() {
        val text = "A".repeat(TextChunker.DEFAULT_MAX_CHUNK_SIZE)
        val chunks = TextChunker.chunkText(text)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks.single())
    }

    @Test
    fun chunkText_withCustomLimit_respectsMaxChunkSizeAndSentenceBoundaries() {
        val sentence1 = "First sentence ends cleanly."
        val sentence2 = "Second sentence also ends cleanly."
        val sentence3 = "Third sentence completes the text."
        val fullText = "$sentence1 $sentence2 $sentence3"

        val chunks = TextChunker.chunkText(fullText, maxChunkSize = 65)

        assertTrue("Expected multiple chunks, got ${chunks.size}", chunks.size >= 2)
        assertTrue(chunks.all { it.length <= 65 })
        assertEquals("$sentence1 $sentence2", chunks[0])
        assertEquals(sentence3, chunks[1])
    }

    @Test
    fun chunkText_closingQuoteAtExactBoundary_neverExceedsLimit() {
        val text = "He said \"hello.\" Next sentence follows here."
        // "He said \"hello.\"" is 16 chars (closing quote at index 15)
        val exactFit = TextChunker.chunkText(text, maxChunkSize = 16)
        assertEquals(
            listOf("He said \"hello.\"", "Next sentence", "follows here."),
            exactFit
        )

        // At 15 chars the closing quote would exceed the limit, so it stays out of chunk 0
        val quoteOverflow = TextChunker.chunkText(text, maxChunkSize = 15)
        assertEquals(
            listOf("He said \"hello.", "\" Next", "sentence", "follows here."),
            quoteOverflow
        )
    }

    @Test
    fun chunkText_withoutSpacesOrPunctuation_hardCutsAtCustomLimit() {
        val text = "x".repeat(250)
        val chunks = TextChunker.chunkText(text, maxChunkSize = 100)

        assertEquals(3, chunks.size)
        assertEquals(100, chunks[0].length)
        assertEquals(100, chunks[1].length)
        assertEquals(50, chunks[2].length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun chunkText_nonPositiveLimit_throws() {
        TextChunker.chunkText("hello", maxChunkSize = 0)
    }

    @Test
    fun splitInHalf_shortUnspacedText_doesNotThrow() {
        val halves = TextChunker.splitInHalf("abcdefghij")
        assertTrue(halves.isNotEmpty())
        assertEquals("abcdefghij", halves.joinToString(""))
    }
}
