# OpenAI-Compatible Providers (SlothSpeak)

SlothSpeak supports any OpenAI Chat Completions-compatible API provider via a generic multi-provider system. You can add, configure, and switch between providers entirely from the Settings UI — no code changes required.

## Supported Providers

| Provider | Base URL | Models | Thinking | Key Required |
|----------|----------|--------|----------|--------------|
| **Z.ai** | `https://api.z.ai/api/paas/v4/` | `glm-5.3`, `glm-5.3-flash` | Always-on by default; `thinking.type` = enabled/disabled | Yes |
| **DeepSeek** | `https://api.deepseek.com/v1/` | `deepseek-v4-flash` | Enabled by default; `thinking.type` takes precedence | Yes |
| **Custom** | User-defined | User-defined (e.g. `qwen3.8-27b`) | Off by default; `sendThinkingParams` toggle | Optional |

## Configuration

Providers are added and edited through the same provider dialog as built-in LLM
providers (Settings → LLM → Add model). The list only shows configured providers;
the active provider is highlighted and exposes its thinking chip + Modify action.

Each provider instance has:
- **Display Name** — friendly label shown in UI
- **Base URL** — must include up to and including `/v1/` (e.g. `https://api.z.ai/api/paas/v4/`)
- **Model ID** — the model string sent in the `model` field (e.g. `glm-5.3`, `deepseek-v4-flash`, `qwen3.8-27b`)
- **API Key** — stored in EncryptedSharedPreferences; optional for Custom providers (no Authorization header when blank)
- **Thinking toggle** — enable/disable reasoning/thinking for this provider
- **Reasoning Effort** — Low / High / Max (only sent when thinking is enabled)
- **Send thinking parameters** — advanced toggle: ON for Z.ai/DeepSeek templates, OFF for Custom (vLLM/Ollama may 400 on unknown `thinking`/`reasoning_effort` fields)
- **Max Tokens** — optional; DeepSeek defaults to 8192 when thinking is enabled

## Thinking / Reasoning Trace

### Capture
When a compatible provider returns `reasoning_content` in the Chat Completions response, it is:
1. Captured as `reasoningText` in the `QAPair` entity
2. Persisted to the Room database
3. Displayed as a collapsible 'View thinking' / 'Hide thinking' section in `QAPairCard`

### Important notes
- **GLM effort fallback**: Z.ai silently falls back to `max` (extremely slow) if `reasoning_effort` is missing or invalid. Always send explicit effort.
- **DeepSeek CoT consumes max_tokens**: Chain-of-thought reasoning counts toward `max_tokens`. Default is 8192 when thinking enabled.
- **Never echo reasoning_content in request history**: Including `reasoning_content` in request messages causes DeepSeek to reject with HTTP 400, and wastes tokens on other providers. History messages (user/assistant pairs) only carry `role` and `content`.

## Self-Hosted (Custom) Providers

For self-hosted models (e.g. Qwen 3.8 27B via vLLM, Ollama, LM Studio):

| Platform | Example Base URL | Notes |
|----------|-----------------|-------|
| **vLLM** | `http://192.168.1.100:8000/v1/` | Add `--reasoning-parser` flag to emit `reasoning_content` |
| **Ollama** | `http://localhost:11434/v1/` | OpenAI-compatible endpoint; may not support `reasoning_content` |
| **LM Studio** | `http://localhost:1234/v1/` | OpenAI-compatible endpoint; local inference |

When using Custom providers:
- API key is **optional** — leave blank for local/self-hosted
- **Send thinking parameters** is OFF by default (vLLM etc. may 400 on unknown fields)
- The client still parses `reasoning_content` whenever present in the response
- For vLLM with `--reasoning-parser`, the response will include `reasoning_content` alongside `content`

## Architecture

```
Settings UI → CompatibleProviderManager (SharedPrefs JSON)  →  OpenAiCompatibleClient
                     ↓                                           ↓
              ApiKeyManager (EncryptedSharedPrefs)         ChatCompletionsService (Retrofit)
                                                           POST /chat/completions
```

## Request Format

```json
{
  "model": "glm-5.3",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant..."},
    {"role": "user", "content": "Previous question"},
    {"role": "assistant", "content": "Previous answer"},
    {"role": "user", "content": "Current question"}
  ],
  "thinking": {"type": "enabled"},
  "reasoning_effort": "high",
  "max_tokens": 8192
}
```

History messages **never** carry `reasoning_content`. The `thinking` and `reasoning_effort` fields are omitted when thinking is disabled or when `sendThinkingParams` is false for the provider.

---

## Tool use (implemented)

Instead of pre-fetched MCP search context, compatible providers now use a ReAct agent
loop with first-class tools. When at least one tool plugin is enabled **and** configured in
Settings → Tools, the request goes through `AgentHarness`: the model may call
`brave_web_search` (Brave Search API) or `http_request` (curl-like HTTP), results are fed
back, and the loop repeats until the model answers in text. Mutating HTTP requests
(POST/PUT/PATCH/DELETE) require user approval in a confirmation dialog. See the
"Tool Harness Architecture" section of the main [README](../README.md) for details.

Tool-calling support depends on the server exposing the OpenAI `tools` / `tool_calls`
Chat Completions fields; self-hosted models without tool-calling support should keep the
tools disabled in Settings.
