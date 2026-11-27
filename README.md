# SlothSpeak

SlothSpeak is an open-source, bring-your-own-keys Android app for voice conversations with LLMs. It makes the opposite trade-off from most voice assistants: maximum response quality, not minimum latency.

## Why This Exists

Other voice apps use lighter models optimized for low latency. SlothSpeak uses the most capable AI models at maximum reasoning effort, accepts that responses may take minutes, and delivers the answer as natural-sounding speech. The result is voice-driven access to the highest-quality AI reasoning available, without touching a keyboard.

There is no server backend. The app calls AI provider APIs directly from the device.

<table>
  <tr>
    <td><img src="docs/1.jpg" width="300" /></td>
    <td><img src="docs/2.jpg" width="300" /></td>
  </tr>
  <tr>
    <td><img src="docs/3.jpg" width="300" /></td>
    <td><img src="docs/4.jpg" width="300" /></td>
  </tr>
</table>

## Features

**Voice and text Q&A** — Ask by voice or type with the chat composer. Typed questions are answered silently (text only) with a per-answer speaker button that synthesizes and plays the reply on demand; spoken questions are answered with voice playback.

**Interactive voice mode** — Hands-free continuous conversation using on-device voice activity detection (Silero VAD). After an answer plays back, the app asks "Do you have a follow-up?" and listens for the next question. Say a dismissal phrase ("no", "I'm done", "that's all", …), stay silent, or tap "Stop Conversation" to end. Not available for Deep Research models. Configurable silence timeout (1–10 s, default 2 s) and an 8-second no-speech timeout.

**Wake word** — Optional "Hey Jarvis" wake word detection running fully on-device with openWakeWord ONNX models bundled in the app (`app/src/main/assets/wakeword/`). Saying the phrase starts a new question. Detection runs only while the app is in the foreground and no pipeline/recording is active.

**On-device streaming STT (English + French)** — Pick FUTO as the Speech-to-Text provider to transcribe locally with the Nemotron 3.5 streaming model (0.6 B, int8) via sherpa-onnx. Words stream onto the capture screen while you speak; when recording stops, the transcript is editable with a character counter and auto-sends 2 seconds after your last edit (or immediately via **Send now**). If live recognition returns no text, the recorded WAV is re-run through batch transcription so the question is not lost. Download the ~650 MB model once from Hugging Face, or import an existing sherpa-onnx model folder. After that, no API key and no network are needed for transcription. English, French, or automatic language selection.

**Cloud STT** — OpenAI (`gpt-4o-transcribe`), Together (`openai/whisper-large-v3`), or any custom OpenAI-compatible transcription endpoint. Reuses the OpenAI key or a dedicated STT key.

**Four built-in LLM providers plus OpenAI-compatible ones** — OpenAI (GPT-6.2 Astra / Sol, GPT-6.1 Terra, GPT-6 Luna), Gemini (4 Carbon / Neon / Hydrogen / Argon + Deep Research Max), Claude (Opus 5.5 / Fable 5.2 / Sonnet 5.5 / Haiku 5.5), Grok (4.7), and any OpenAI Chat Completions-compatible endpoint: Z.ai GLM 5.3, DeepSeek V4 Flash, or a custom/self-hosted API (Qwen via vLLM/Ollama/LM Studio, …). GPT-6.x, Gemini 4, and Claude expose configurable reasoning effort / thinking level. See [Supported Models](#supported-models).

**Tool use (agent harness)** — For OpenAI-compatible providers, the model can use tools in a ReAct loop: **Brave Web Search** (read-only web search) and **HTTP Request** (curl-like GET/POST/PUT/PATCH/DELETE/HEAD). Mutating HTTP requests require explicit user approval — Allow once, Always allow host, Always allow all, or Deny — with secrets redacted in the confirmation preview. Every tool call is recorded as a trace (name, arguments, result preview, duration, OK/error) that is persisted with the answer and shown as a collapsible "Tools used (N)" section. See [Tool Harness Architecture](#tool-harness-architecture).

**Deep Research** — OpenAI and Gemini deep research models perform extended async research with polling, delivering thorough answers backed by web sources.

**X search** — Grok supports optional X (Twitter) search for real-time posts.

**Reasoning traces** — Compatible providers' `reasoning_content` is captured, saved to the database, and displayed as a collapsible "View thinking" section in the answer card.

**Citations** — OpenAI Deep Research and Grok responses with citations display numbered footnotes and a sources list. Clean text (without citation markers) is sent to TTS for natural reading.

**TTS providers** — OpenAI (`gpt-4o-mini-tts`, 13 voices with audio preview, editable reading-style instructions), Together (`canopylabs/orpheus-3b-0.1-ft`), any custom OpenAI-compatible speech endpoint, or the Android system TTS engine (free and offline).

**Playback controls** — Pause/resume, rewind 20 s, forward 30 s, seek bar across chunks, adjustable speed (0.5x–2.0x). Optional notification sound and 3-second pause before playback begins. Mute mode skips playback entirely while still saving the conversation.

**Audio focus & Bluetooth** — Coexists with other audio apps (e.g. Spotify pauses during recording/playback and resumes during processing). Microphone input is automatically routed through a connected Bluetooth headset (classic SCO and BLE).

**Media session controls** — Headset media buttons and notification transport controls (play, pause, stop) work during answer playback.

**Conversation history** — Browse past conversations, replay audio, mark favorites, delete conversations, or delete audio only to reclaim storage. Multi-turn follow-ups chain via `previous_response_id` (OpenAI, Grok) or replayed history (Gemini, Claude, compatible providers).

**Retry from failure** — If any pipeline step fails (transcription, LLM, TTS, playback), retry resumes from that step without re-running earlier steps.

**Background processing** — The entire pipeline runs inside an Android foreground service with partial wake locks (65 minutes for the pipeline, 30 for playback), so it continues even if the user navigates away.

**Encrypted API key storage** — All API keys are stored with AES-256 encryption via AndroidX Security-Crypto `EncryptedSharedPreferences`.

**No intermediate server** — The app does not phone home, collect analytics, or transmit telemetry. All API calls go directly from your device to the provider. Your questions and answers are subject to each provider's own data and privacy policies.

## Supported Models

| Provider | Model | ID | Notes |
|----------|-------|----|-------|
| OpenAI | GPT-6.2 Astra | `gpt-6.2-astra` | Default; configurable reasoning effort (medium/high/xhigh) |
| OpenAI | GPT-6.2 Sol | `gpt-6.2-sol` | Configurable reasoning effort (medium/high/xhigh) |
| OpenAI | GPT-6.1 Terra | `gpt-6.1-terra` | Configurable reasoning effort (medium/high/xhigh) |
| OpenAI | GPT-6 Luna | `gpt-6-luna` | Configurable reasoning effort (medium/high/xhigh) |
| Google | Gemini 4 Carbon | `gemini-4-carbon` | Default; configurable thinking level (low/medium/high) |
| Google | Gemini 4 Neon | `gemini-4-neon` | Configurable thinking level (low/medium/high) |
| Google | Gemini 4 Hydrogen | `gemini-4-hydrogen` | Configurable thinking level (low/medium/high) |
| Google | Gemini 4 Argon | `gemini-4-argon` | Configurable thinking level (low/medium/high) |
| Google | Gemini Deep Research Max | `deep-research-max-preview-04-2026` | Async polling with Google Search; no thinking levels |
| Anthropic | Claude Opus 5.5 | `claude-opus-5-5` | Default; adaptive thinking, effort (low/medium/high/max) |
| Anthropic | Claude Fable 5.2 | `claude-fable-5-2` | Adaptive thinking, effort (low/medium/high/max) |
| Anthropic | Claude Sonnet 5.5 | `claude-sonnet-5-5` | Adaptive thinking, effort (low/medium/high/max) |
| Anthropic | Claude Haiku 5.5 | `claude-haiku-5-5` | Adaptive thinking, effort (low/medium/high/max) |
| xAI | Grok 4.7 | `grok-4.7` | Optional X (Twitter) search |

OpenAI Deep Research (`o3-deep-research`) is temporarily unavailable because OpenAI retired the model in July 2026. Gemini Deep Research (`deep-research-max-preview-04-2026`) remains available. Migration to GPT-6 Astra + web search is tracked in issue #19.

Each built-in provider requires its own API key, configured in Settings. Compatible providers are configured in the same dialog (base URL, model ID, optional key). Speech-to-Text can use OpenAI, Together, a custom endpoint, or the on-device FUTO provider (no key). Text-to-Speech can use OpenAI, Together, a custom endpoint, or the Android system engine. Details on compatible providers: [docs/compat-providers.md](docs/compat-providers.md).

### On-device STT model

The FUTO provider runs [NVIDIA Nemotron 3.5 ASR Streaming](https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11) (0.6 B, multilingual) through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). The 560 ms int8 ONNX export is downloaded on demand (~650 MB) from Hugging Face and can be deleted from Settings.

While you speak, microphone frames are fed to the recognizer and partial text streams onto the capture screen. Stopping the recording opens a review step with an editable transcript and a live character counter; the question is sent to the LLM 2 seconds after the last edit, or immediately via **Send now**. If live recognition returns no text, the recorded WAV is sent through the regular batch transcription path so the question is not lost.

If you already have the model on the device, choose **Import model folder** and pick the directory containing `encoder.int8.onnx` (or `encoder.onnx`), `decoder.int8.onnx`, `joiner.int8.onnx`, and `tokens.txt`. The files are copied into app storage and renamed to the canonical names. FUTO Keyboard and FUTO Voice Input use private GGUF/`.bin` models, which sherpa-onnx cannot load, so those files cannot be reused directly.

Model weights are licensed under OpenMDW 1.1; sherpa-onnx is Apache-2.0. Wake word models are from [openWakeWord](https://github.com/dscripka/openWakeWord) (Apache-2.0, see `app/src/main/assets/wakeword/ATTRIBUTION.md`).

## End-to-End Pipeline

```
Capture audio (M4A via MediaRecorder, or WAV via AudioRecord for streaming STT/VAD)
    → STT (cloud: POST audio/transcriptions · on-device: sherpa-onnx streaming)
    → LLM step (per active provider)
         built-in provider → dedicated client (OpenAI / Gemini / Claude / Grok)
         compatible provider → AgentHarness tool loop:
             adapter.step → tool calls → ToolRegistry.execute → results fed back
             → repeat (≤ 8 steps) until the model answers in text
    → Save answer to Room (updated early-saved QAPair)
    → Split answer into chunks at sentence boundaries per synthesizer (4096 cloud, 3000 on-device — see docs/tts-limits.md)
    → TTS all chunks in parallel (3 concurrent) → MP3/WAV files
    → Play sequentially with seek/speed controls, audio focus, MediaSession
    → Interactive mode: follow-up prompt + VAD listening → next question
```

The pipeline is a state machine driven by `SlothSpeakService.PipelineState` (`Idle → Transcribing → Transcribed → Thinking → UsingTool → AwaitingToolConfirmation → ThinkingComplete → GeneratingAudio → Playing → Complete`, plus `ListeningForFollowUp`, `InteractiveEnding`, and `Error` with the failed step for retry). Every state change is emitted to the UI through a `StateFlow` and mirrored in the foreground notification.

Two performance-minded details: the conversation and a placeholder QAPair are saved to Room *before* the LLM call (updated after the answer arrives, so nothing is lost on cancel), and text-only questions skip TTS entirely — the speaker button on the answer card synthesizes audio on demand and persists the file paths.

## Architecture

```
Compose UI (MainActivity, screens, components)
        │ collects StateFlows, calls actions
        ▼
ViewModels (Conversation / Settings / History)
        │ bind to service, own recording + wake word lifecycle
        ▼
SlothSpeakService (bound + started foreground service)
        │ orchestrates the pipeline, playback, follow-up loop, notifications
        ├── api/ clients (OpenAI, Gemini, Claude, Grok, OpenAiCompatible)
        ├── harness/ AgentHarness + LlmChatAdapter   ← compatible providers only
        │       └── tools/ ToolRegistry → ToolPlugins (BraveSearch, HttpRequest)
        ├── audio/ recorders (MediaRecorder, PCM+VAD, streaming), player, focus, BT routing
        ├── data/ ApiKeyManager, CompatibleProviderManager, ToolConfigManager (encrypted prefs)
        └── data/db/ Room (conversations, qa_pairs) → history & replay
```

- **Foreground service** — `SlothSpeakService` is bound by the ViewModels and started as a foreground service (`mediaPlayback|microphone`; degrades to `mediaPlayback` when the mic permission is not granted). The pipeline runs in a service coroutine scope; playback runs on a separate job so replaying an old answer doesn't kill an in-flight pipeline.
- **UI layer** — Single activity + Jetpack Compose + Material 3 (dark theme). ViewModels expose `StateFlow`s (pipeline state, session items, provider lists, settings state) that composables collect; all service interaction goes through the binder.
- **Storage** — Room (v6, `slothspeak_db`) for conversations and QAPairs (including favorites, reasoning text, rich text with citations, serialized tool traces). All secrets (provider keys, Brave key, host-auth header values) live in `EncryptedSharedPreferences` (master key AES-256-GCM). Non-secret config (compat provider list, tool toggles, write policy) lives in plain `SharedPreferences` as JSON.

## Code Overview

`app/src/main/java/com/slothspeak/`:

| Package / file | Responsibility |
|---|---|
| `MainActivity.kt` | Single activity: Compose host, navigation (`main`/`settings`/`history`), foreground lifecycle hooks for wake word + follow-up listening |
| `SlothSpeakApplication.kt` | Application class, Room DB singleton |
| `api/` | One Retrofit client per built-in provider + the Chat Completions client |
| `├ OpenAIClient.kt` | OpenAI STT (`audio/transcriptions`), LLM (Responses API + Deep Research with polling), citation extraction |
| `├ GeminiService.kt` / `GeminiClient.kt` | Gemini generateContent + Deep Research with polling |
| `├ ClaudeService.kt` / `ClaudeClient.kt` | Claude Messages API with adaptive thinking |
| `├ GrokService.kt` / `GrokClient.kt` | Grok Responses API (OpenAI-compatible), X search tool |
| `├ ChatCompletionsService.kt` / `OpenAiCompatibleClient.kt` | Generic Chat Completions calls for Z.ai/DeepSeek/custom, incl. tool-call steps, `reasoning_content` capture |
| `├ ModelDiscovery.kt` | Optional `/models` listing for the provider dialog |
| `└ models/` | Moshi request/response types (Responses, Speech, Transcription, Gemini, Claude, Grok, Chat Completions) |
| `audio/` | Recording, playback, speech engines |
| `├ AudioRecorder.kt` | MediaRecorder wrapper (M4A/AAC), 5-minute limit with countdown |
| `├ PcmAudioRecorder.kt` | AudioRecord PCM/WAV capture with Silero VAD auto-stop; feeds the streaming STT frames |
| `├ VadAudioRecorder.kt` | VAD recorder for interactive-mode follow-up listening (WAV output, silence/no-speech timeouts) |
| `├ FutoSttEngine.kt` | On-device sherpa-onnx streaming recognizer (Nemotron 3.5) + `FutoStreamingSession` |
| `├ WakeWordDetector.kt` | openWakeWord ONNX pipeline (melspectrogram → embedding → "Hey Jarvis" classifier) |
| `├ TtsProcessor.kt` | Chunks text and synthesizes all chunks in parallel (3 concurrent via Semaphore) |
| `├ TtsSynthesizer.kt` | `TtsSynthesizer` interface; OpenAI-compatible HTTP impl + `OnDeviceTtsSynthesizer` (Android system engine) |
| `├ AudioPlayer.kt` | Sequential playback: pause/resume/stop, cross-chunk seeking, speed |
| `├ AudioFocusManager.kt` | Audio focus lifecycle (recording vs playback modes, transient-loss resume, phone-call detection) |
| `└ BluetoothAudioRouter.kt` | Mic routing to Bluetooth (API 31+ setCommunicationDevice / legacy SCO) |
| `config/` | |
| `└ SystemPrompts.kt` | Default Q&A, Deep Research and follow-up prompts (editable in Settings) |
| `data/` | Preferences, provider config, DB |
| `├ ApiKeyManager.kt` | Encrypted prefs for all keys + model/voice/effort selection + STT/TTS provider config; model-ID constants |
| `├ CompatibleProviders.kt` | `CompatibleProviderConfig` (Z.ai/DeepSeek/custom presets) + manager (config JSON in plain prefs, keys encrypted) |
| `├ LlmProvider.kt` | `LlmProviderType` / `LlmProviderUiState` UI models for configured providers |
| `├ LlmProviderCatalog.kt` | Single source of truth for configured providers, active model, switching (testable store abstraction) |
| `├ SpeechProvider.kt` | `SttProviderUiState` / `TtsProviderUiState` |
| `├ FutoSttModel.kt` | On-device STT model spec, download/resume/delete/import manager (Hugging Face) |
| `├ ToolConfigManager.kt` | Tool toggles, Brave key, HTTP write policy, allowed write hosts, host-auth profiles (secrets encrypted) |
| `└ db/` | Room: `SlothSpeakDatabase` (v6, migrations), `ConversationDao`, entities `Conversation`, `QAPair` (incl. `toolCallsJson`, reasoning, rich text) |
| `harness/` | Agent loop |
| `├ AgentHarness.kt` | Minimal ReAct loop: adapter.step → execute tools → feed results back, ≤ 8 steps, tool errors returned to the model, joins reasoning across steps |
| `├ LlmChatAdapter.kt` | `LlmChatAdapter` interface + `HarnessMessage` model + OpenAI Chat Completions mapping (`OpenAiCompatibleChatAdapter`) |
| `service/` | |
| `└ SlothSpeakService.kt` | The orchestrator: pipeline state machine, transcription dispatch, per-provider LLM step, tool-harness wiring + confirmation bridge, TTS, playback, follow-up loop, wake-word foreground, notifications, wake locks, retry, replay |
| `tools/` | Tool harness |
| `├ ToolPlugin.kt` | Plugin interface (enabled × configured gating, tool definitions, execution) |
| `├ ToolRegistry.kt` | Routes calls to owning plugin; unknown tool/exception/timeout → `isError` results; truncates outputs |
| `├ ToolTypes.kt` | `ToolDefinition` (MCP-aligned schema), `ToolCall`, `ToolResult`, `ToolTraceEntry` (Moshi-serializable), confirmation types |
| `├ ToolSummaries.kt` | Human-readable one-liners for status display and traces |
| `└ plugins/BraveSearchPlugin.kt`, `plugins/HttpRequestPlugin.kt` | See [Tool Harness Architecture](#tool-harness-architecture) |
| `ui/` | Compose UI |
| `├ screens/MainScreen.kt` | Home: conversation list, composer entry, recording overlay, pipeline status, playback controls, tool-confirmation dialog |
| `├ screens/SettingsScreen.kt` | Settings host: LLM / Tools / STT / TTS / Voice / Playback / Interactive / Wake Word / System prompts / History management |
| `├ screens/LlmSettingsSection.kt` | Unified provider list (built-in + compatible): add/edit dialog, model discovery, thinking chips |
| `├ screens/ToolsSettingsSection.kt` | Tool toggles, Brave key, write policy, allowed hosts, host-auth profiles |
| `├ screens/SpeechSettingsSection.kt` | STT/TTS provider cards + dialogs, FUTO model management, voice preview |
| `├ screens/RecordingOverlay.kt` | Capture overlay: manual countdown / VAD indicator / live transcript / transcript editor with auto-send |
| `├ screens/HistoryScreen.kt` | History list with favorites, detail + replay, delete / delete-audio-only |
| `├ components/ChatComposer.kt` | Bottom text composer (mic + send) with model pill below |
| `├ components/ModelSwitcher.kt` | Active-model pill + expandable provider/model switcher |
| `├ components/QAPairCard.kt` | Q&A card: question, answer, "View thinking", "Tools used (N)" trace, speaker/replay buttons |
| `├ components/StatusIndicator.kt` | Pipeline status (thinking/using tool/awaiting confirmation/generating), cancel/retry, playback controls |
| `├ components/ToolConfirmationDialog.kt` | Write-approval dialog (method, URL, redacted headers, body preview; fails closed on dismiss) |
| `├ components/RecordButton.kt` | Pulsing stop button |
| `└ theme/` | Dark Material 3 theme (pure black + green/blue accents), typography |
| `util/` | |
| `├ TextChunker.kt` | Splits text per synthesizer (4096 cloud, 3000 on-device — see `docs/tts-limits.md`) at sentence boundaries (falls back to word boundaries) |
| `├ CitationFormatter.kt` | Extracts URL citations → clean text (TTS) + rich text (numbered footnotes + sources) |
| `└ SpeechTextCleaner.kt` | Strips markdown from Gemini Deep Research output for natural TTS reading |
| `viewmodel/` | |
| `├ ConversationViewModel.kt` | Service binding, session state, recording lifecycle (manual/VAD/streaming), text questions, playback controls, wake word lifecycle, model switcher state |
| `├ SettingsViewModel.kt` | Provider CRUD/activation, thinking levels, STT/TTS config, prompts, interactive & wake word settings, history management |
| `└ HistoryViewModel.kt` | Conversation browsing, favorites, delete, delete-audio-only, replay resolution |

Tests live in `app/src/test/java/com/slothspeak/` and mirror this structure (api, audio, data, db, harness, tools, ui screenshot tests).

## Provider Architecture

**Built-in providers** (OpenAI, Gemini, Claude, Grok) each have a dedicated Retrofit client because their API shapes differ. Model IDs are constants in `ApiKeyManager` (e.g. `MODEL_PRO = "gpt-5.5-pro"`), grouped in `modelsForBuiltin(provider)`. Per-provider state: one API key + one selected model (stored in encrypted prefs). Multi-turn context: OpenAI/Grok chain via `previous_response_id`; Gemini/Claude send explicit history loaded from Room.

**OpenAI-compatible providers** (Z.ai, DeepSeek, custom) are instances of `CompatibleProviderConfig` managed by `CompatibleProviderManager` — provider list as a JSON array in plain prefs, per-provider API keys in encrypted prefs. All go through one `OpenAiCompatibleClient` (Chat Completions). Each instance carries: display name, base URL (up to and including `/v1/`), model ID, API key, thinking toggle + reasoning effort, a `sendThinkingParams` switch (off for custom providers so vLLM/Ollama don't 400 on unknown fields), and optional max tokens. History is always replayed as plain user/assistant pairs — `reasoning_content` is never echoed back (DeepSeek rejects it).

**Catalog** — `LlmProviderCatalog` unifies both families into `LlmProviderUiState` for the Settings screen and the home-screen model switcher; `LlmProviderStore` abstracts the preference reads so unit tests can use an in-memory store (EncryptedSharedPreferences needs the AndroidKeyStore, which Robolectric can't provide).

**Adding a built-in provider/model** means: add the model-ID constant + list in `ApiKeyManager`, wire key/model getters, and call the provider's API from a client class + a branch in `SlothSpeakService`. **Adding a compatible provider/model needs no code at all** — enter base URL + model ID in Settings → LLM → Add model (with optional `/models` discovery).

## Tool Harness Architecture

Tools are only offered to **OpenAI-compatible providers** (Z.ai, DeepSeek, custom) — built-in providers use their own native clients. See the note in [Known Limitations](#known-limitations).

- **`ToolPlugin`** — the plugin interface: `isConfigured()` (credentials present) × `isEnabled()` (user toggle). A plugin participates in the agent loop only when both are true. `tools()` returns MCP-aligned `ToolDefinition`s (JSON Schema forwarded verbatim as OpenAI `tools[].function.parameters`); `execute()` may simply throw — the registry converts failures to `isError` results.
- **`ToolRegistry`** — routes each `ToolCall` to the active plugin owning the tool. Unknown/disabled tools, exceptions and timeouts become `isError` results so the loop never crashes. Outputs are truncated (16,000 chars default). Per-call timeout: 30 s default; the production registry uses 10 minutes because the timeout also covers user time spent on write-confirmation prompts (the plugins' own OkHttp clients bound network time separately). Caller-scope cancellation still propagates.
- **`AgentHarness`** — the ReAct loop: build messages (system prompt → history pairs → question), `adapter.step(messages, activeTools)`; if the model requests tools, execute them and append assistant `tool_calls` + `role=tool` results, then repeat. Stops when the model answers in text or after `maxSteps` (8), at which point one final tool-less step synthesizes an answer. Tool failures are returned to the model as error content so it can retry or answer anyway.
- **`OpenAiCompatibleChatAdapter`** — maps `HarnessMessage` to Chat Completions wire format (assistant tool-call turns replayed with empty `content`; tool results sent as `role=tool` with `tool_call_id` only).
- **Write confirmation** — `HttpRequestPlugin` runs GET/HEAD unattended; POST/PUT/PATCH/DELETE suspend the pipeline (`PipelineState.AwaitingToolConfirmation`) until the user answers `ToolConfirmationDialog`: Allow once / Always allow host (persisted) / Always allow all (persisted policy) / Deny. No handler or a broken confirmation flow fails closed — nothing is sent. Redirects are never followed automatically (a redirected write needs its own confirmation); response bodies are capped at 256 KB; HTML is reduced to readable text. Per-host auth profiles (saved header name/value pairs, values encrypted) are attached automatically and always override model-supplied values for the same header; secrets are redacted (`••••••`) in the preview.
- **`BraveSearchPlugin`** — read-only `brave_web_search` (query, count 1–10, freshness) against the Brave Search API with the key from encrypted prefs. Read-only, so it never triggers a confirmation prompt.
- **Tool traces** — every execution records a `ToolTraceEntry` (name, arguments, 500-char result preview, error flag, duration). The trace list is serialized to JSON, persisted on the QAPair (`toolCallsJson`), and rendered as a collapsible "Tools used (N)" section with OK/Error badges.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| HTTP | OkHttp 4 + Retrofit 2 |
| JSON | Moshi (codegen) |
| Database | Room |
| Secret storage | EncryptedSharedPreferences (AES-256) |
| Audio recording | MediaRecorder (M4A/AAC) + AudioRecord (PCM/WAV for VAD & streaming STT) |
| Voice activity detection | Silero VAD (android-vad, ONNX Runtime Mobile) |
| On-device STT | sherpa-onnx 2.0.2 (vendored AAR in `app/libs/`) + Nemotron 3.5 streaming model |
| Wake word | openWakeWord ONNX models (ONNX Runtime Java API, `app/libs/onnxruntime-android-1.28.2.jar`) |
| Audio playback | MediaPlayer |
| Background work | Foreground Service + Kotlin Coroutines |
| Min / target / compile SDK | 26 (Android 8.0) / 35 / 35 |

## Building

Prerequisites: JDK 21 (the project compiles to JVM 17 bytecode), Android SDK 35, and a `local.properties` containing `sdk.dir=/path/to/android-sdk` (git-ignored).

```bash
git clone https://github.com/JonesSteven/SlothSpeak.git
cd SlothSpeak
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

You will need at least one API key (OpenAI, Google, Anthropic, xAI, Z.ai, DeepSeek, or a self-hosted endpoint) to use the app. Keys are entered in Settings; none are required at build time.

### Tests

```bash
./gradlew test
```

JVM unit tests run under Robolectric (`app/src/test/java/com/slothspeak/`, covering the tool plugins, harness mapping, registry, provider catalog, config managers, and API JSON mapping). UI composables are covered by Roborazzi screenshot tests that render headlessly and write PNGs to `app/build/ui-screenshots/` (build output, not committed). If you change Compose UI code, the screenshot tests must pass; add or update cases only when the visuals actually change.

There is also one connected-device instrumentation test (`app/src/androidTest/…/FutoSttOnDeviceTest.kt`) that downloads the real ~650 MB on-device STT model and transcribes bundled English/French samples — run with `./gradlew connectedDebugAndroidTest` on a physical device.

## Known Limitations

- Tools are available only for OpenAI-compatible providers; the built-in OpenAI/Gemini/Claude/Grok clients do not run the agent loop.
- No transcript review for cloud STT providers — with OpenAI/Together/custom STT the question goes straight to the LLM (the editable transcript step is FUTO-only). Web search as a hosted tool is not exposed for built-in providers.
- TTS retry regenerates all chunks rather than only failed ones.
- No progressive playback (all TTS chunks must finish before audio starts).
- On-device STT requires a ~650 MB model (downloaded or imported); wake word supports only the bundled "Hey Jarvis" phrase.
- Interactive voice mode is unavailable for Deep Research models, and follow-up listening is cancelled when the app goes to the background.
- OpenAI Deep Research is temporarily unavailable because `o3-deep-research` was retired by OpenAI in July 2026; Gemini Deep Research (`deep-research-max-preview-04-2026`) remains available; migration to GPT-6 Astra + web search is tracked in issue #19.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, code style, and PR guidelines. For security reports, see [SECURITY.md](SECURITY.md).

## License

MIT — see [LICENSE.md](LICENSE.md).
