# Text-to-Speech Input Limits & Chunking Strategy

## Background (Issue #9)

SlothSpeak previously split all TTS answers with a single hardcoded limit of 4096 characters (matching OpenAI's `/v1/audio/speech` input cap). When on-device TTS was selected, long responses between 4001 and 4096 characters were sent as a single utterance to Android's `TextToSpeech.synthesizeToFile(...)`, which rejected them synchronously with:

```
On-device TTS request rejected (code=-1)
```

(`TextToSpeech.ERROR == -1`).

## Engine Limits

| Synthesizer | Limit | Source |
|---|---|---|
| OpenAI-compatible (`/v1/audio/speech`) | 4096 chars (`DEFAULT_MAX_CHUNK_SIZE`) | API input limit |
| Android On-Device (`TextToSpeech`) | `TextToSpeech.getMaxSpeechInputLength()` (4000 chars on AOSP) | Android SDK (`android.speech.tts.TextToSpeech`) |

Note: Third-party TTS engines installed on Android can report different values from `TextToSpeech.getMaxSpeechInputLength()` or reject inputs near the boundary. `OnDeviceTtsSynthesizer` therefore queries `TextToSpeech.getMaxSpeechInputLength()` dynamically and applies headroom rather than hardcoding 4000.

## Chunking Strategy

1. **Per-synthesizer limit (`TtsSynthesizer.maxChunkChars`)**:
   - `TtsSynthesizer` exposes `val maxChunkChars: Int`, defaulting to `4096` (`TextChunker.DEFAULT_MAX_CHUNK_SIZE`) for OpenAI-compatible endpoints.
   - `OnDeviceTtsSynthesizer` overrides `maxChunkChars` using `min(TextToSpeech.getMaxSpeechInputLength() - 1, 3000)` clamped to at least `1`. On AOSP (4000-char API limit), this yields `3000` characters per chunk, leaving ~1000 characters of headroom below the engine limit while avoiding tiny, choppy utterances.
2. **Sentence-boundary splitting (`TextChunker.chunkText`)**:
   - `TextChunker.chunkText(text, maxChunkSize = DEFAULT_MAX_CHUNK_SIZE)` accepts the synthesizer's `maxChunkChars`.
   - It searches backward up to 500 characters from `maxChunkSize` for sentence-ending punctuation (`.`, `!`, `?`, plus an optional closing `"` that fits within `maxChunkSize`), falling back to the last space in that window, and finally a hard cut at `maxChunkSize` if no whitespace exists.
3. **Recursive fallback (`TtsProcessor`)**:
   - `TtsProcessor.processText` splits using `synthesizer.maxChunkChars`.
   - Its existing recursive `TextChunker.splitInHalf` path remains active for cloud providers that enforce tighter token limits (`"maximum input limit"` errors).
4. **Actionable diagnostics**:
   - When `TextToSpeech.synthesizeToFile` rejects an utterance, `OnDeviceTtsSynthesizer` logs and throws `code`, `chars`, and `engineLimit` so failures are immediately diagnosable.

## Error Recovery

If TTS still fails (for example, missing voice data or an unexpected engine error), `StatusIndicator` displays both **Retry** (when retryable) and **Cancel**. Tapping **Cancel** calls `dismissError()` to return the pipeline to `Idle`, persist the already-generated LLM text answer (when available), clean up any incomplete temporary audio files, and allow immediate follow-up questions while keeping the answer readable on screen.
