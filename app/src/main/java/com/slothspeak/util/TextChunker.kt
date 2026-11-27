package com.slothspeak.util

object TextChunker {

    const val DEFAULT_MAX_CHUNK_SIZE = 4096
    private const val MIN_SEARCH_RANGE = 500

    private val SENTENCE_ENDINGS = setOf('.', '!', '?')

    fun chunkText(text: String, maxChunkSize: Int = DEFAULT_MAX_CHUNK_SIZE): List<String> {
        require(maxChunkSize > 0) { "maxChunkSize must be positive, was $maxChunkSize" }
        if (text.length <= maxChunkSize) {
            return listOf(text)
        }

        val chunks = mutableListOf<String>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxChunkSize) {
                chunks.add(remaining)
                break
            }

            val boundary = findSplitPoint(remaining, maxChunkSize)
            chunks.add(remaining.substring(0, boundary).trimEnd())
            remaining = remaining.substring(boundary).trimStart()
        }

        return chunks.filter { it.isNotBlank() }
    }

    fun splitInHalf(text: String): List<String> {
        val midpoint = text.length / 2
        val boundary = findSplitPoint(text, midpoint + MIN_SEARCH_RANGE)
        val first = text.substring(0, boundary).trimEnd()
        val second = text.substring(boundary).trimStart()
        return listOf(first, second).filter { it.isNotBlank() }
    }

    private fun findSplitPoint(text: String, maxLength: Int): Int {
        val effectiveMax = maxLength.coerceAtMost(text.length)
        // Search backward from the max length for a sentence boundary
        val searchStart = (effectiveMax - MIN_SEARCH_RANGE).coerceAtLeast(0)

        for (i in effectiveMax - 1 downTo searchStart) {
            val char = text[i]
            if (char in SENTENCE_ENDINGS) {
                // Check for closing quotes after the punctuation without exceeding effectiveMax
                val nextIdx = i + 1
                if (nextIdx < text.length && text[nextIdx] == '"' && nextIdx + 1 <= effectiveMax) {
                    return nextIdx + 1
                }
                return nextIdx
            }
        }

        // No sentence boundary found - fall back to last space
        for (i in effectiveMax - 1 downTo searchStart) {
            if (text[i] == ' ') {
                return i + 1
            }
        }

        // No space found either - hard cut at max length
        return effectiveMax.coerceAtLeast(1)
    }
}
