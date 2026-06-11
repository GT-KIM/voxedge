# LLM track: persistent KV session, model selection, on-device tool use

Status: implemented, host-tested, and **device-verified 2026-06-10** (Galaxy Z Fold7 / SM8750,
driven headlessly over adb via the `debug_typed_turn` intent — see checklist results at the end).
Builds on the 2026-05 loop: mic → VAD → ASR → LLM → clause segmenter → TTS.

Three on-device-only findings were caught and fixed during verification:
1. `GenieSamplerConfig_createFromJson` needs the `{"sampler": {"type": "basic", ...}}` wrapper
   (bare object → error -8 JSON_SCHEMA).
2. `GENIE_DIALOG_PARAM_CONTEXT_OCCUPANCY` returns occupied TOKENS, not a percent — normalized
   against `context.size` in the JNI.
3. Genie strips special tokens from the response stream, so Qwen's native `<tool_call>` wrapper
   never reaches the app — the tool-call markers are plain-text `[TOOL_CALL]…[/TOOL_CALL]`.

## 1. Persistent KV session (session-wise turns)

Previously every turn replayed the whole transcript: `genie_llm.cpp` called `GenieDialog_reset`
after each query, so multi-turn cost grew with history length and TTFT included re-prefilling all
prior turns.

Now the Genie KV cache persists across turns:

- **Warm turn**: only the incremental user block is sent (`ChatTemplate.incremental`); the system
  prompt and earlier turns are already in KV. No history replay, minimum prefill, lower TTFT.
- **Re-prefill** (`LlmEngine.resetSession()` + `ChatTemplate.full`) happens when (policy in
  `core/LlmSessionPolicy`, JVM-tested):
  - first turn / engine cold;
  - the user **switches language** (the in-KV system prompt pins the reply language);
  - the previous query ended in `ABORTED` (barge-in), `CONTEXT_EXCEEDED`, or `ERROR`
    (KV no longer matches the transcript — barged turns are excluded from history);
  - **context occupancy ≥ 80 %** (`GENIE_DIALOG_PARAM_CONTEXT_OCCUPANCY`) — re-prefilling with the
    capped recent-turn transcript is also how old turns get *evicted*, since KV entries can't be
    dropped piecemeal.
- The transcript history (`ConversationController.history`, 6 turns) stays the durable source of
  truth; KV is a cache of it plus whatever older turns haven't been evicted yet.

Native additions (`genie_llm.cpp`): status-returning `nativeGenerate` (OK / CONTEXT_EXCEEDED /
ABORTED / ERROR via sentence code + `GENIE_STATUS_WARNING_*`), `nativeReset`,
`nativeContextOccupancy`, `nativeSetSampling`.

**Sampling**: `GenieDialog_getSampler` + `GenieSamplerConfig_createFromJson` +
`GenieSampler_applyConfig` at runtime. App default **temp 0.6** (bundle ships 0.7; the 2026-05-30
review recommended trying 0.4–0.6), top-k 20 / top-p 0.8 unchanged. Defaults live in
`LlmSampling` / `LlmCatalog`; `shared/config/config.schema.json` `llm.sampler` + `llm.kv_session`
mirror them.

## 2. Model selection (multi-backend)

- `LlmEngine` is now the only thing the pipeline sees. Engines declare a **chat template id**
  (`chatml` / `gemma` / `raw`); `core/ChatTemplate` renders full / incremental / tool-response
  prompts, so the controller is model-agnostic.
- `LlmCatalog` (code-as-registry): `qwen3-4b-genie` (default, Genie HTP, measured) and
  `gemma4-e2b-litert` (LiteRT-LM `.litertlm`, GPU backend first). Selection persists in
  SharedPreferences via the in-app `LLM:` button and applies on next launch; a missing model falls
  back to whatever is provisioned.
- **LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android:0.13.1`, required the Kotlin 2.2.21
  toolchain bump): `LiteRtLlm` wraps Engine/Conversation. LiteRT-LM owns template AND history, so
  it is a `raw` engine — plain user text per turn, system prompt delivered via `setSystemPrompt`
  at session creation, session = the stateful `Conversation`. Known limitations (documented in
  code): no mid-generation cancel in the Kotlin API yet (abort only drops the stream), no
  occupancy report, no transcript replay after reset (restarts from the system prompt).
- Fetch: `converter/phase1/fetch_gemma4_litert_lm.sh`
  (`litert-community/gemma-4-E2B-it-litert-lm`); provisioning in MODELS.md
  (`files/llm_litert/gemma-4-E2B-it.litertlm`).

## 3. On-device tool use (agentic foundation)

Speech-driven agentic turns, fully offline:

```
user speech → ASR → LLM step ─ plain text ──────────────→ clause segmenter → TTS
                     │   ▲
                     │   └ <tool_response> (warm-session incremental query)
                     └ <tool_call>{json}</tool_call> → ToolCallFilter (never spoken)
                                                      → ToolRegistry.dispatch → result
```

- **Format**: `<tool_call>{"name": ..., "arguments": {...}}</tool_call>` — the Qwen-family native
  convention; tool list + rules are a composed system-prompt module
  (`PromptAssembler.toolsModule`).
- **`core/ToolCallFilter`** sits between LLM tokens and UI/TTS: text before a call is spoken
  ("One moment."), the JSON block (even split across token chunks) is suppressed, partial blocks
  at stream end are dropped — JSON can never reach the voice.
- **Loop** (`SpeechTurnRunner`): ≤ `MAX_TOOL_STEPS` (3) generation steps per turn; each tool
  result is fed back as `ChatTemplate.toolResponse` on the **warm KV session** (this is why the
  tool loop requires `sessionCapable` and why workstream 1 is its foundation). Barge-in safety: a
  stale generation id breaks the loop **before** dispatch, so a cancelled turn never fires a
  side-effecting tool.
- **v1 tools** (`devicetools/DeviceTools`, all airplane-mode-safe): `get_datetime`, `set_timer`,
  `set_alarm` (system clock app, `SET_ALARM` normal permission), `battery_status`, `flashlight`.
  Tool *results* are model-facing plain English; the model answers the user in the user's
  language.
- Events: `tool.call` / `tool.result` / `tool.step_limit` (additive, runtime-log-v1).

Next steps on this foundation: LiteRT-LM's native `tools`/`automaticToolCalling` for the Gemma
path; richer tools (notes, calendar, media, app launch); user confirmation policy for
destructive/outward actions; multi-call steps.

## 4. Follow-up track (2026-06-11): measurement + latency/quality levers

- **Eval harness** (`tools/llm/eval_turns.py` + `eval_prompts.json`): drives the standing KO/EN +
  tool-task battery through the `debug_typed_turn` adb hook, joins logcat replies with the JSONL
  event log, and emits a markdown report (TTFT/first-PCM/total per turn, session_mode, tool
  success rate, reply text). The measurement backbone for every model/sampling/prompt A/B.
- **Response cap**: `GenieDialog_setMaxNumTokens` wired as `LlmEngine.setMaxResponseTokens`
  (default 120 = config `llm.max_response_tokens`), persisted + live-adjustable in settings.
  Bounds runaway-answer latency/thermal (a long uncapped answer measured 36 s total).
- **KV rewind** (SDK "KV$ Rewind"): re-prefill turns on rewind-capable engines send the full
  transcript with `GENIE_DIALOG_SENTENCE_REWIND`; Genie prefix-matches the KV cache and prefills
  only the divergence, so post-barge-in / language-switch / eviction recovery costs ~the delta.
  `prompt.assembled.session_mode` now logs `warm | rewind | full`.
  **Device findings (2026-06-11):** rewind against an EMPTY cache fails hard
  (`GENIE_STATUS_ERROR_QUERY_FAILED` -6 before any token), so the controller only rewinds when
  occupancy > 0; with content cached, rewind works on the Qwen3 w4a16 bundle (3/3 OK in the eval
  battery). A language-switch rewind costs ~full prefill (the system prompt diverges at the
  start, so there is no shared prefix — TTFT ~500–650 ms vs ~385 ms plain); the payoff case is
  post-barge-in recovery, where system+history match. GenieLlm additionally SELF-HEALS: a rewind
  failing with zero output disables rewind for the process and retries plain, so unsupported
  bundles degrade safely.
- **Rolling summary on eviction** (config `rolling_summary_tokens`): at >=80 % occupancy the
  controller first distills the conversation on the still-warm session (`session.summary` event),
  folds it into the system prompt ("Summary of the conversation so far: ..."), keeps only the
  freshest 2 turns verbatim, and re-prefills. Long sessions keep continuity past the window.
- **Tool-call corrective retry**: a malformed/unterminated `[TOOL_CALL]` block (small-model
  failure mode) triggers ONE corrective `<tool_response>` instructing valid JSON or a plain
  answer (`tool.malformed_retry` event) instead of silently dropping the attempt.
- **NATIVE function calling for LiteRT-LM (device-verified 2026-06-11)**: `LiteRtToolAdapter`
  bridges the ToolRegistry into the runtime's `OpenApiTool`/`ToolProvider` API with
  `automaticToolCalling` — the engine formats/parses calls in the model's trained format, so
  engines with `handlesToolsNatively` skip the prompt-convention module and the `[TOOL_CALL]`
  filter loop entirely. Result: Gemma 4 E2B, which misread tool output under the prompt
  convention, answered "It is five thirty-seven in the afternoon" — correct, natural, 6 s total.
- **Confirmation policy for side-effecting tools (device-verified 2026-06-11)**: `ToolSpec.sideEffect`
  (timer/alarm/flashlight) + a turn-aware gate in `ToolRegistry.dispatch` shared by BOTH tool
  paths: the first call is deferred with "CONFIRMATION REQUIRED — ask the user"; a re-call on the
  NEXT turn (after the user confirmed) executes; pendings expire if not re-called. Settings
  toggle "Ask before device actions" (default off). Verified flow: "set a timer for 2 minutes" →
  gated, model asks → "yes please do it" → executed, real timer in the clock app.
- **Rolling summary — device-verified (2026-06-11)**: a 23-turn conversation crossed 81 %
  occupancy → `session.summary` (ok, 403 chars, ~6 s on the warm session) → re-prefill with
  history trimmed 6→2 + summary in the system prompt → occupancy collapsed to 27 % and the next
  turn was warm again (3× window compaction with continuity preserved).
- **Throughput spikes — both parked with findings**: (a) Genie speculative decoding: AI Hub ships
  no ready Qwen3 draft bundle (only Llama 3.2 3B SSD), so `bindEngine("draft")` needs a custom
  0.6B export. (b) LiteRT `Backend.NPU` fails `NOT_FOUND` on the generic Gemma `.litertlm` — the
  NPU path needs an NPU-specific model variant; the backend fallback chain (npu→gpu→cpu) makes
  retrying trivial when one is published. GPU stays the Gemma default.
- **Baseline reports** (output/llm_eval/, local): Qwen3 Genie — TTFT 385 ms full / 77 ms warm,
  rewind turns 504–646 ms, tool tasks 2/3 called (+1 answered correctly by reusing an earlier
  tool result from context); Gemma 4 GPU — TTFT ~402–801 ms full / 111 ms warm, tools 3/3,
  noticeably weaker answer quality. Qwen3 Genie remains the default.

## On-device verification results (2026-06-10, Z Fold7 / SM8750, typed turns over adb)

1. **Session** ✅ — cold turn TTFT 385 ms / first PCM 972 ms; **warm turn TTFT 76 ms / first PCM
   534 ms** (5× TTFT win from skipping the history re-prefill). `session_mode` logged `full` →
   `warm` as designed; a Korean turn after English forced `full` with `lang:"KO"` and a Korean
   reply. Occupancy reads sane (0 → ~20 % over three turns). Still to do: a long session crossing
   80 % (eviction re-prefill) and a hands-free VAD run (mic path untestable with the phone locked).
2. **Sampler** ✅ — `sampler updated …"temp":0.600… ok=true` after the wrapper fix. Temp 0.6 vs
   0.7 A/B on the QA set still to be judged by ear.
3. **Tools** ✅ — "what time is it right now?" → `tool get_datetime({}) -> ok=true` → spoken reply
   with the *real* device time; "set a timer for 3 minutes" → `set_timer({minutes=3, label=…})`
   → a live timer notification in the Samsung Clock app; Korean turn also tool-called correctly.
   No JSON ever reached TTS/UI. `tool.call`/`tool.result` events in the JSONL log. First model
   attempt used a bare tool name — the prompt's exact-format example + "MUST call the matching
   tool" rule fixed compliance.
4. **Gemma 4 (LiteRT-LM)** ✅ (2026-06-11) — `litert-lm(gpu):gemma-4-E2B-it.litertlm` loads and
   runs the full loop on the GPU backend: cold TTFT 782 ms / first PCM 1061 ms; warm TTFT 107 ms /
   first PCM 470 ms; the tool loop works on the RAW engine (`get_datetime` dispatched + spoken).
   Two device findings: (a) API 31+ needs `<uses-native-library libOpenCL*.so>` manifest entries
   or the GPU delegate fails with an INTERNAL compiled-model error (CPU fallback added too);
   (b) E2B answer quality is clearly below Qwen3-4B (misread the tool's "2:54 PM" as
   "twenty-five forty-five PM") — Qwen3 Genie stays the default. Still to measure: tok/s, RSS,
   10-min thermal, Korean quality.
5. **Settings UI** ✅ (2026-06-11) — sheet verified on screen: model picker shows
   active/selected/provisioned truthfully ("Active now: …", "loads on next launch" + restart
   notice), sampling sliders apply live + persist (and reset to per-model defaults), tools/barge-in
   toggles persist. Model selection round-trip Gemma→Qwen3 across a restart works.
5. **Headless harness** — `adb shell am start -n com.conversationalai.agent/.ui.MainActivity
   --es debug_typed_turn "text"` drives a full typed turn (works behind the lockscreen);
   MainActivity is `singleTop` so repeated invocations reuse the resident engines.
