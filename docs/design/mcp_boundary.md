# MCP boundary: tools and the LLM → TTS handoff

Status: implemented on Android 2026-10-06, JVM-tested and device-verified (see the end of this
note); ASR server added 2026-10-09 (section "ASR as a third server"); iOS has a build-ready mirror
(`apps/ios/Core/Mcp.swift`, uncompiled).

## Requirement

The project brief requires the LLM output / TTS input exchange to be MCP-formatted for later
expansion, and (ruling of 2026-10-06) the on-device tools to be exposed through MCP as well. The
earlier contract (`shared/mcp/README.md`, 2026-05-30) had reconciled "MCP" into a streaming event
channel plus a turn record and said so explicitly; that reconciliation no longer stands.

## Constraints that shaped the design

- **Clause streaming must survive.** First audio is ~0.55-0.66 s only because the first short
  clause is synthesized while the LLM is still decoding. Any boundary that waits for a complete
  answer, or inlines audio in JSON, would add seconds.
- **Offline, no local network bridge.** Standing policy: the runtime has no network permission and
  no localhost/HTTP/WebSocket bridge.
- **Two tool-call paths already exist.** Genie (prompt-convention `[TOOL_CALL]`) and LiteRT-LM
  (native function calling) both end in `ToolRegistry.dispatch`; the confirmation gate and the
  dispatch observer live there.
- **No JSON library in JVM tests.** `org.json` is a throwing stub in Android JVM unit tests, so the
  codec is hand-rolled (`core/mcp/Json.kt`), like the existing `ToolCallParser`.

## Design

```
                 LLM side (MCP client)                       MCP servers (in-process)
  LLM tokens ──► ToolCallFilter / native FC ──► ToolRegistry.dispatch ──► McpToolBridge.call
                                                                 │  tools/call (JSON-RPC 2.0)
                                                                 ▼
                                                   McpServer "voxedge-device-tools"
                                                   └─ ToolRegistry.serve: gate + tool + observer
  LLM tokens ──► ClauseSegmenter ──► SpeechTurnRunner ──► McpTts.speak
                                                                 │  tools/call "speak"
                                                                 ▼
                                                   McpServer "voxedge-tts"
                                                   └─ ClauseInputBuilder + TtsEngine → pcm_ref
                  ◄── PCM by handle ◄──────────────────────────────┘
  VAD utterance ──► ConversationController ──► McpAsr.transcribe
                                                                 │  tools/call "transcribe" (pcm_ref)
                                                                 ▼
                                                   McpServer "voxedge-asr"
                                                   └─ AsrEngine (owned / platform) → text
```

- `core/mcp/Mcp.kt`: `McpServer` (initialize, notifications/initialized, ping, tools/list,
  tools/call), `McpClient` (blocking request API with timeout), `McpTransport` seam,
  `InProcessTransport.pair()`, `McpMessageTap` for observability.
- `core/mcp/McpToolBridge.kt`: serves a `ToolRegistry` as MCP. `ToolRegistry.dispatch` now
  encodes the model's call as `tools/call` and returns the decoded result; the server handler
  calls `ToolRegistry.serve` (confirmation gate, execution, observer). Nothing upstream changed:
  both backends, the eval harness, and the existing tests see the same `dispatch` surface.
- `core/mcp/McpTts.kt`: one tool `speak(text, language, clause_index, chunk_id, flow_steps,
  generation_id)`; result `structuredContent {pcm_ref, sample_rate, num_samples, synth_ms, ...}`.
  The client redeems `pcm_ref` for the float buffer out of band. The retry-once for transient
  native synthesis failures moved to the server side.
- `SpeechTurnRunner` constructs `McpTts` around the injected `TtsEngine` + `ClauseInputBuilder`
  and calls `speak` per clause; the consumer/producer pipelining is unchanged. Raw MCP traffic is
  logged as `mcp.request` / `mcp.response` events (direction, method, id, error, bytes; never
  arguments or text).
- Shared profile: `shared/mcp/README.md`, fixtures in `shared/mcp/fixtures/`, conformance test
  `tests/test_mcp_contract.py` (fixture validity, Android/iOS parity of protocol revision, methods,
  server and tool names, and that dispatch/speak go through MCP).

## ASR as a third server (2026-10-09)

Leader question: which large, independently runnable components are worth exposing through MCP?
Assessment (chat, 2026-10-09): only ASR, plus read-only state as resources later. The LLM does not
fit (`tools/call` has no token stream; a per-answer boundary would break the first-audio budget,
and MCP's own LLM primitive `sampling/createMessage` runs the other way). VAD, capture, playback,
the segmenter, and the prompt assembler are hot-path or pure and have no external consumer. Memory
is already served as device tools. Leader: "ASR 분리 진행".

- `core/mcp/McpAsr.kt`: server `voxedge-asr`, one tool `transcribe`. In-process the controller
  stores the endpointed float buffer under a `pcm_ref` and the handler takes it back (same buffer,
  no copy); an external caller sends `audio_wav` (base64 16-bit PCM WAV, decoded by
  `McpAsr.wavSamples`, the inverse of `McpTts.wavBytes`). Result: transcript as the text block
  and `structuredContent {text, asr_ms, engine, sample_rate, num_samples, utterance_id}`. Optional
  `engine` (`owned` | `platform`) and `language` (`ko` | `en`) are resolved by hooks the activity
  supplies (platform only after its language-pack gate); the loop never passes them.
- Every ASR call in the app goes through it: `ConversationController.transcribeSamples` (hands-free
  and speculative), push-to-talk, the diagnostics ASR test, and the `debug_asr_wav` hook (which
  now uses the `engine`/`language` arguments instead of flipping `SwitchableAsr`). Enforced by
  `tests/test_mcp_contract.py::test_every_asr_call_goes_through_mcp`.
- Recognition is serialized with a static `ASR_LOCK` (one recognizer per engine), mirroring the
  TTS server's `SYNTH_LOCK`, so a host call cannot decode concurrently with a live utterance.
- External endpoint: `McpExternalEndpoint.forAsr`, abstract socket `voxedge-mcp-asr`, no result
  post-processing. Host: `python tools/mcp/mcp_client.py --port 7779 tools/call transcribe
  --audio ko.wav language=ko engine=owned`.
- MCP traffic of the ASR leg is logged as `mcp.request` / `mcp.response` with `server:
  voxedge-asr` and no `generation_id` (none is assigned yet at ASR time).
- A protocol or tool error on the loop path is treated as "nothing heard" (`asr.no_speech` with
  an `error` attribute), never as a crash.
- iOS mirror: `McpAsr` in `apps/ios/Core/Mcp.swift` (uncompiled).

What it does not do: VAD endpointing, language auto-detection, or streaming partials. A
`language` passed from outside switches the live engine's language (same effect as the existing
`debug_asr_wav` hook), and the diagnostics toggle does not learn about it.

## Decisions and alternatives

| Choice | Alternative considered | Why |
| --- | --- | --- |
| In-process transport, real wire format | stdio / local socket now | Policy forbids a local network bridge; the servers are transport-agnostic, so attaching a transport later is additive. Whether a real transport is wanted is still an open leader question |
| `tools/call speak` per clause | MCP request per full answer | Latency: the clause is the unit the loop already streams |
| PCM by handle in `structuredContent` | MCP `audio` content block (base64) | 144k floats per clause would dominate the message and copy twice; the existing contract already forbids inlined audio |
| Gate and observer stay on the server | Move them into the bridge | Keeps the confirmation semantics in one place for both backends and leaves `ToolRegistryTest` meaningful |
| Hand-rolled JSON codec | kotlinx-serialization / org.json test dependency | No new dependency or Gradle plugin; the envelope is small and the codec is unit-tested |
| ASR `transcribe` per endpointed utterance, audio by handle | Streaming partials over MCP notifications; LLM `generate` as a tool | The loop already works on finished utterances; partials and the LLM token stream have no `tools/call` shape and would cost first-audio latency |

## Verification

- JVM: `.\gradlew.bat :app:testDebugUnitTest` — 18 new MCP tests (codec, server/client round trip,
  protocol errors, timeout, bridge, TTS server) plus the existing runner/tool/registry suites pass
  unchanged (2026-10-06).
- Host: `python -m unittest discover -s tests` includes `test_mcp_contract.py`.
- Device: see the dated entry below.

### Device run (2026-10-06, Galaxy Z Fold7 / SM8750, typed turns over adb)

- **LLM -> TTS boundary (Gemma 4 E2B, LiteRT-LM GPU):** every clause produced a `tools/call speak`
  request/response pair in `turn_events.jsonl` (`mcp.request` out/in, `mcp.response` out/in,
  ~190-210 bytes request, ~276 bytes response). Encode/decode overhead is ~1 ms per leg. Turn
  "18 plus 47 equals what?": TTFT 127 ms, first PCM 538 ms (within the published 0.55-0.66 s band),
  3 clauses, 3 speak calls.
- **Tool boundary (Qwen3-4B Genie, prompt convention):** "what is the battery level?" -> MCP
  `tools/call battery_status` (96-byte request, 129-byte response) wrapped around
  `tool.call` / `tool.result`, then 3 `speak` calls; reply read the real battery state.
  "what time is it right now?" called `get_datetime` through MCP twice (the step-limit guard then
  stopped the chain) - the repeated call is a known Genie behaviour, not an MCP effect.
- A first device run exposed a logging bug: the MCP taps were detached in the LLM `finally`
  block while the TTS consumer was still synthesizing, so the responses of the last clauses were
  missing. Fixed (taps detach after the consumer drains) and covered by
  `SpeechTurnRunnerToolTest.mcpTrafficForToolsAndEveryClauseIsLoggedAcrossTheWholeTurn`.
- Gemma did not call a tool in these three prompts (answered inline, 18 + 47 as "fifty-five");
  that is the tool-selection behaviour already measured in `docs/llm/tool_eval.md`, unchanged by
  this work. The device was returned to its Gemma selection afterwards.

### External endpoint device check (2026-10-06, Z Fold7, Qwen3 selected)

- Enabled headlessly (`--ez debug_mcp_endpoint true`); logcat `McpExternal` showed both abstract
  sockets listening. `adb forward tcp:7777 localabstract:voxedge-mcp-tools` / `tcp:7778 ... -tts`.
- `tools/list` from the host returned the 13 device tools with JSON-Schema `inputSchema`.
- `tools/call get_datetime` returned the device's local time; `calculate expression=18+47` → `65`;
  an unknown tool came back as JSON-RPC error `-32602` with `data.available` (protocol error, as
  specified).
- `tools/call speak text="Hello from the host client." language=en --save hello.wav`: 2.02 s of
  44.1 kHz audio inline (`audio/wav`, 178,220 bytes, peak 0.28 / RMS 0.046 — real speech, not
  silence); `structuredContent` carried `audio_bytes` and no `pcm_ref`. Synth 298 ms.
- Disabled with `--ez debug_mcp_endpoint false`; both sockets closed. Forwards removed.
- Not exercised: a host call arriving during a live spoken turn (the `SYNTH_LOCK` serialization is
  unit-level reasoning, not device-tested), and the diagnostics UI switch itself (the headless hook
  drives the same handler).

### ASR server device check (2026-10-09, Z Fold7, airplane mode on, debug APK)

- Launched with `--ez debug_mcp_endpoint true`; logcat `McpExternal` showed all three sockets
  listening (`voxedge-mcp-tools`, `voxedge-mcp-tts`, `voxedge-mcp-asr`).
- `adb forward tcp:7779 localabstract:voxedge-mcp-asr`; `tools/list` returned `transcribe` with its
  JSON-Schema `inputSchema`.
- `tools/call transcribe --audio ko.wav language=ko engine=owned` (56,424 samples, 16 kHz): text
  " 그는 괜찮은 척하려고 애쓰는 것 같았다.", `asr_ms` 136-176 over three calls, engine
  `sherpa-onnx zipformer-ko`. Without `engine`/`language` the same engine answered (the loop's
  current selection). `engine=platform`: "그는 괜찮은 척 하려고 애쓰는 것 같았다", `asr_ms`
  389-411, gate logged `usable=true (on-device ko-KR installed)`. Both transcripts match the
  2026-10-06 comparison.
- Errors: no audio -> `isError` "missing audio: pass 'pcm_ref' or 'audio_wav'"; `engine=cloud` ->
  `isError` "engine 'cloud' is not available"; unknown tool -> JSON-RPC `-32602` with
  `data.available: ["transcribe"]`.
- In-process path through the same server: `--es debug_asr_wav ko --es debug_asr_engine owned`
  -> 86 ms, identical text; `platform` -> 335 ms, identical text. (The owned engine's `asr_ms` is
  higher on the external socket thread than in-process; not investigated.)
- Endpoint closed with `--ez debug_mcp_endpoint false`; forward removed.
- Not exercised: a live hands-free utterance through `ConversationController` (needs a speaker;
  covered by the JVM controller tests, which now run their fake ASR through `McpAsr`), and the
  `mcp.request`/`mcp.response` lines with `server: voxedge-asr` in `turn_events.jsonl`.

## Follow-ups

- ~~Transport~~ Done 2026-10-06 (leader: "C1 추가"): `McpExternalEndpoint` serves the same
  `McpServer` objects over Android abstract local sockets (`voxedge-mcp-tools`, `voxedge-mcp-tts`),
  newline-delimited JSON-RPC, off by default, toggled from diagnostics or `debug_mcp_endpoint`.
  Host client: `tools/mcp/mcp_client.py` via `adb forward ... localabstract:...`. TTS results ship
  audio inline as an `audio` block. Synthesis is serialized with the live loop (`SYNTH_LOCK`).
  Device check recorded below.
- ASR server (2026-10-09): done, see "ASR as a third server" and the device check above.
- Read-only runtime state (thermal level, degrade actions, selected engines) as MCP `resources`
  on the external endpoint: assessed as worthwhile, not implemented, not yet approved.
- iOS: the mirror is uncompiled until a Mac path exists (C4).
- `TurnRecord.tool_calls` in the event schema can carry the JSON-RPC ids once the record is
  emitted from the Android side.
