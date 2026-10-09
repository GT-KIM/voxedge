# Conversation Contract: MCP boundaries + runtime events + durable turn record

This directory holds the contract between pipeline stages (ASR → prompt → LLM → tools → TTS →
playback) and between the two platforms. It has three parts:

1. **MCP boundaries** (this file, `fixtures/`). The LLM's tool calls, the LLM-output → TTS-input
   clause handoff, and the captured-utterance → ASR handoff are **Model Context Protocol**
   exchanges: JSON-RPC 2.0 messages between an in-process MCP client on the loop side and
   in-process MCP servers. MCP is a project requirement; the profile
   below is what both platforms implement.
2. **Runtime streaming events** ([`conversation_events.schema.json`](conversation_events.schema.json),
   `Event`). Low-latency, ordered events between stages (`asr.final`, `llm.text_delta`,
   `tts.chunk_request`, `tts.audio_chunk`, `control.*`, `mcp.request`, `mcp.response`, ...). Audio is
   referenced by handle, never inlined. Each event carries `seq` and `t_mono_ms` for the latency
   waterfall. The Android `RuntimeEventLogger` (`runtime-log-v1`) is the concrete realization.
3. **Durable turn record** (same schema, `TurnRecord`). Aggregated after each turn: text, spoken
   text, timings, `tool_calls`. A replay/analytics/eval log.

## MCP profile

Protocol revision `2025-06-18`, JSON-RPC 2.0, one JSON object per message. Transport is a seam:
the shipped runtime uses an **in-process transport** (no local network bridge, airplane-mode
runtime unchanged). The same server objects can additionally be exposed through an **external
endpoint** (off by default, switched on in the diagnostics panel or with
`--ez debug_mcp_endpoint true` on the launch intent): newline-delimited JSON-RPC over Android
abstract local sockets `voxedge-mcp-tools`, `voxedge-mcp-tts`, and `voxedge-mcp-asr`. Nothing
listens on a TCP port and no network permission is involved; a host reaches them through adb:

```
adb forward tcp:7777 localabstract:voxedge-mcp-tools
python tools/mcp/mcp_client.py --port 7777 tools/list
python tools/mcp/mcp_client.py --port 7777 tools/call calculate expression="18+47"
adb forward tcp:7778 localabstract:voxedge-mcp-tts
python tools/mcp/mcp_client.py --port 7778 tools/call speak text="Hello there." language=en --save hello.wav
adb forward tcp:7779 localabstract:voxedge-mcp-asr
python tools/mcp/mcp_client.py --port 7779 tools/call transcribe --audio ko.wav language=ko engine=owned
```

Over the external endpoint a `speak` result carries the audio inline as an MCP `audio` content
block (`audio/wav`, base64) and `structuredContent.audio_bytes` instead of the in-process
`pcm_ref`, since the caller cannot redeem a handle. Symmetrically, an external `transcribe` call
sends its audio inline as the base64 WAV argument `audio_wav` instead of a `pcm_ref`. The
confirmation gate applies to external tool calls exactly as to the model's.

Methods both platforms must implement (server) and use (client):

| Method | Direction | Purpose |
| --- | --- | --- |
| `initialize` | client → server | Handshake; server returns `protocolVersion`, `capabilities.tools`, `serverInfo` |
| `notifications/initialized` | client → server | Handshake complete |
| `ping` | client → server | Liveness |
| `tools/list` | client → server | Tool descriptors: `name`, `description`, `inputSchema` (JSON Schema object) |
| `tools/call` | client → server | `params.name`, `params.arguments` → `result.content[] {type:"text"}`, `result.isError`, optional `result.structuredContent` |

Error handling follows MCP: an unknown tool or bad params is a JSON-RPC **error** (`-32602`, with
`data.available` listing tool names); a tool that ran and failed is a **result** with
`isError: true`. Malformed JSON gets `-32700`, a non-2.0 envelope `-32600`, an unknown method `-32601`.

### Server `voxedge-device-tools`

Serves the on-device tool registry (13 tools on Android: `get_datetime`, `set_timer`, `set_alarm`,
`battery_status`, `flashlight`, `calculate`, `remember_fact`, `recall_facts`, `forget_fact`,
`create_calendar_event`, `dial_number`, `send_sms`, `navigate`). Every parameter is a string in
`inputSchema`; tools coerce. The side-effect **confirmation gate** and the dispatch observer live on
the server side, so they apply identically to the prompt-convention `[TOOL_CALL]` loop and to
engine-native function calling: both end in the same `tools/call`.

### Server `voxedge-tts`

One tool, `speak`: the LLM output, cut into a clause by the segmenter, formatted as MCP and handed
to TTS.

```json
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"speak","arguments":{
  "text":"It is three in the afternoon.","language":"en","clause_index":0,"chunk_id":"c0",
  "flow_steps":6,"generation_id":17}}}
```

```json
{"jsonrpc":"2.0","id":4,"result":{"content":[{"type":"text","text":"synthesized 144384 samples"}],
  "isError":false,"structuredContent":{"pcm_ref":"pcm:c0:1234","sample_rate":44100,
  "num_samples":144384,"synth_ms":227,"chunk_id":"c0","clause_index":0,"language":"en"}}}
```

`structuredContent.pcm_ref` is a handle the client redeems for the PCM buffer out of band. This
keeps the clause-streaming latency path: one short request per clause, encode/decode in
microseconds against a ~220 ms synthesis, no audio in JSON. A synthesis failure is
`isError: true` and the clause is dropped (logged as `tts.chunk_dropped`).

### Server `voxedge-asr`

One tool, `transcribe`: a captured utterance, endpointed by VAD (and optionally enhanced), formatted
as MCP and handed to the speech recognizer. The unit is the finished utterance, so VAD, capture,
and the mic stay outside MCP; the loop's own ASR calls, the push-to-talk path, and the diagnostics
test all go through this server.

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"transcribe","arguments":{
  "pcm_ref":"pcm:u0:5678","sample_rate":16000,"utterance_id":"u0"}}}
```

```json
{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"지금 몇 시야"}],
  "isError":false,"structuredContent":{"text":"지금 몇 시야","asr_ms":84,
  "engine":"sherpa-onnx zipformer-ko (offline, lang=ko)","sample_rate":16000,"num_samples":28800,
  "utterance_id":"u0"}}}
```

In-process the audio travels by handle (`pcm_ref`, the client's own float buffer, no copy). An
external caller passes `audio_wav` (base64 16-bit PCM WAV; multi-channel is averaged to mono)
instead, and may add `engine` (`owned` | `platform`, the platform recognizer only after its
language-pack gate passes) and `language` (`ko` | `en`) for comparison runs; without them the
loop's current engine and language are used. The transcript is the text block and
`structuredContent.text`; an empty transcript is a normal result. A recognizer failure is
`isError: true` and the loop treats the utterance as "nothing heard" (`asr.no_speech`).

### Fixtures

`fixtures/` holds one canonical message per method/direction. The Python conformance test
(`tests/test_mcp_contract.py`) validates them and checks that the Android and iOS method constants
match this profile.

## Why the runtime stays streaming

On-device measurement (`docs/demo/sm8750_measurements.md`) showed that first-audio latency
requires streaming LLM output into TTS clause by clause: first-clause TTS is ~0.67 s only because
a short clause is synthesized immediately. The MCP boundary therefore sits **per clause**
(`speak`), never around a complete answer.

## Cancellation / barge-in (`generation_id`)

Every event carries a `generation_id` (cancel epoch), and so does every `speak` call. On
barge-in/cancel a `control.*` event supplies `cancel.new_generation_id`; all stages drop events with
a stale epoch, and the LLM side never issues a `tools/call` for a stale generation (checked before
dispatch), so a cancelled turn cannot fire a side-effecting tool. Audible playback must stop
mid-clause within `cancel.stop_playback_within_ms` (default 200 ms).

## Versioning

`schema_version` is required on every `Event` and `TurnRecord`. Semver; both platforms pin the
major. Unknown event `type`s and unknown `metadata` keys are ignored, never rejected. MCP messages
carry the protocol revision only in `initialize`.

## Conformance

Both platforms pass a shared suite built from fixtures under `tests/`, asserting invariants rather
than identical model output: schema validation of emitted events, MCP fixture validity and method
parity, canned event stream → identical state transitions, timed/latency fixtures, barge-in
(mid-clause stop < 200 ms), stale-event races, no-speech timeout, thermal degrade, prompt
normalization, clause segmentation.

## Related specs

- Latency waterfall + targets: `docs/design/latency_budget.md`.
- Conversation state machine + power policy: `docs/design/speech_loop_state_machine.md`.
- Typed configuration: `shared/config/`.
- MCP boundary design note: `docs/design/mcp_boundary.md`.
