# MCP boundary: tools and the LLM → TTS handoff

Status: implemented on Android 2026-10-06, JVM-tested and device-verified (see the end of this
note); iOS has a build-ready mirror (`apps/ios/Core/Mcp.swift`, uncompiled).

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

## Decisions and alternatives

| Choice | Alternative considered | Why |
| --- | --- | --- |
| In-process transport, real wire format | stdio / local socket now | Policy forbids a local network bridge; the servers are transport-agnostic, so attaching a transport later is additive. Whether a real transport is wanted is still an open leader question |
| `tools/call speak` per clause | MCP request per full answer | Latency: the clause is the unit the loop already streams |
| PCM by handle in `structuredContent` | MCP `audio` content block (base64) | 144k floats per clause would dominate the message and copy twice; the existing contract already forbids inlined audio |
| Gate and observer stay on the server | Move them into the bridge | Keeps the confirmation semantics in one place for both backends and leaves `ToolRegistryTest` meaningful |
| Hand-rolled JSON codec | kotlinx-serialization / org.json test dependency | No new dependency or Gradle plugin; the envelope is small and the codec is unit-tested |

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

## Follow-ups

- ~~Transport~~ Done 2026-10-06 (leader: "C1 추가"): `McpExternalEndpoint` serves the same
  `McpServer` objects over Android abstract local sockets (`voxedge-mcp-tools`, `voxedge-mcp-tts`),
  newline-delimited JSON-RPC, off by default, toggled from diagnostics or `debug_mcp_endpoint`.
  Host client: `tools/mcp/mcp_client.py` via `adb forward ... localabstract:...`. TTS results ship
  audio inline as an `audio` block. Synthesis is serialized with the live loop (`SYNTH_LOCK`).
  Device check recorded below.
- iOS: the mirror is uncompiled until a Mac path exists (C4).
- `TurnRecord.tool_calls` in the event schema can carry the JSON-RPC ids once the record is
  emitted from the Android side.
