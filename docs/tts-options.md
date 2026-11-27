# On-device TTS options (research for issue #17)

Status: **research only — no implementation decision yet.**
Question: besides the Android system engine and the cloud providers (OpenAI / Together / custom
OpenAI-compatible), can SlothSpeak ship a small, free, OSS text-to-speech engine that is easy to
install and good enough for reading long answers aloud?

## Where TTS lives today

- `audio/TtsSynthesizer.kt` defines `TtsSynthesizer.synthesize(text, outputFile, instructions)`.
  Two implementations:
  - `OpenAiCompatibleTtsSynthesizer` — OpenAI, Together and any custom `/v1/audio/speech` endpoint.
  - `OnDeviceTtsSynthesizer` — `android.speech.tts.TextToSpeech`, i.e. whatever voice the OEM
    installed. Zero download, zero cost, but no control over voice/quality and a hard
    `TextToSpeech.getMaxSpeechInputLength()` limit (4000 chars on AOSP) that currently surfaces as
    `Error -1` (see issue #9 / `docs/tts-limits.md`).
- `audio/TtsProcessor.kt` chunks the answer (`util/TextChunker.kt`), synthesizes up to 3 chunks
  concurrently, retries transient failures and recursively splits on provider token-limit errors.
- Provider choice is stored in `ApiKeyManager` (`TTS_PROVIDER_OPENAI`, `TOGETHER`, `CUSTOM`,
  `ONDEVICE`) and edited in Settings → Speech (`ui/screens/SpeechSettingsSection.kt`).

The FUTO STT integration (`data/FutoSttModel.kt`, provider `STT_PROVIDER_FUTO`) is the existing
pattern for an optional, downloadable on-device model: model catalog, progress, resumable download,
SHA-256 verification, delete/clear. A bundled TTS engine should reuse it.

## What "good fit" means for this app

- CPU-only inference on a mid-range arm64 phone; the pipeline already pays for STT and the LLM call,
  so TTS must stay well under real time for a few hundred characters per chunk.
- Downloadable on demand (not baked into the APK), with a clear size shown in Settings.
- Permissive license and redistributable voices (some voices are trained on non-commercial data —
  check per voice, not just per engine).
- English first (the app's UI and default prompt are English), multilingual is a bonus.
- Per-chunk synthesis works with the existing processor, so no streaming API is required initially.

## Candidate engines

| Option | Runtime | Approx. size per voice/model | Languages | License | Notes |
| --- | --- | --- | --- | --- | --- |
| Android system TTS (today) | OS built-in | 0 | OEM | OEM | Fallback only. No voice control, engine length limit, quality varies by device. |
| **Piper** (VITS → ONNX) | sherpa-onnx | ~20–60 MB (16 kHz "low" to 22.05 kHz "high" tiers) | 30+ locales, 100+ voices | Piper: MIT, sherpa-onnx: Apache-2.0 | Best size/latency trade-off, very wide voice catalog, quality is "good" not "great". |
| **Kokoro-82M** | sherpa-onnx | ~80–100 MB INT8 (up to ~330 MB fp32) | mostly English | Apache-2.0 | Widely considered the quality leader for its size; noticeably slower than Piper on CPU. |
| Matcha-TTS | sherpa-onnx | ~50 MB + vocoder | several | Apache-2.0 | Natural prosody, extra moving parts (acoustic model + vocoder). |
| Kitten TTS | ONNX | tens of MB | English | check | Newer, smaller voice set; less independent benchmarking. |
| Pocket TTS | CPU | small | check | check | Reported fast first-audio latency (~1.7 s in a 2026 CPU benchmark), but little deployment evidence on Android. |
| Distilled Kokoro (community) | ONNX | 5–8 MB INT8 | English | inherits Apache-2.0 (verify) | Experimental: tiny and fast, but quality drops; interesting for low-end devices only. |

sherpa-onnx (Apache-2.0) is the common denominator: it runs Piper, Kokoro, Matcha and VITS voices
through ONNX Runtime on Android, iOS, desktop and WebAssembly, and there are existing Android TTS
apps built on it. That makes it the lowest-risk runtime if we add one bundled engine.

## Suggested approach (for decision)

1. Add an optional provider `sherpa` ("On-device — Piper"), implemented as a new
   `SherpaOnnxTtsSynthesizer : TtsSynthesizer`, so the existing processor, chunking, progress and
   playback code stay untouched. Android system TTS remains the zero-download fallback.
2. Ship a small curated voice catalog (start with 1–3 Piper voices; optionally Kokoro-82M INT8 as a
   higher-quality English option) hosted as pinned release assets with SHA-256 checks, downloaded
   through the existing FUTO-style model manager UI (size, progress, delete).
3. Chunk at ~1000–2000 chars for latency (the new per-synthesizer `maxChunkChars` from #9), even
   though Piper has no 4000-char engine limit.
4. Add sherpa-onnx native libraries per ABI (the app already ships FUTO `jniLibs`), and keep the
   voice models out of the APK.

## Open questions (need a decision before implementation)

- First engine: **Piper** (multilingual, ~20–60 MB, fast) or **Kokoro-82M** (best English quality,
  ~90 MB INT8, slower), or both behind one "On-device (downloadable)" provider?
- Acceptable download size per voice, and is on-demand download acceptable (recommended) or should a
  voice be bundled for offline-first use?
- Which locales matter first besides English (French is a plausible second for this project)?
- Confirm that adding ~5–15 MB per ABI of native runtime is acceptable.

## Risks

- APK size / ABI splits and Google Play size limits if the runtime is bundled for all ABIs.
- Model hosting and integrity (pinned URLs + hashes, clear failure UX).
- Voice licensing varies per dataset; the catalog must record the license per voice.
- First-run download UX on slow networks; needs resumable download like FUTO STT.
- CPU thermal throttling during long read-aloud sessions.
