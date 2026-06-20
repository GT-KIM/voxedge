# Tool-calling eval

Quantifies how well each LLM backend uses the offline tools — the thing the teardown calls
"hit-or-miss" — so prompt/model/decoding changes can be judged by numbers instead of vibes.

## What it measures

For a fixed golden set (`tools/llm/tool_eval_set.json`, KO + EN), per backend:

1. **Tool selection** — did the model call the expected tool for a tool task, and call **no** tool
   for a chat task?
2. **Argument extraction** — for a correctly-selected call, were the argument values right
   (numbers, phone digits, math expressions)?
3. **False positives** — chat prompts that wrongly trigger a tool.

The harness reads the actual call arguments from a dedicated `ToolEval` logcat line that
`SpeechTurnRunner` emits for **both** backends (prompt-convention Genie *and* native-FC Gemma —
the latter became observable with the 2026-06-20 A1 unification). The persistent JSONL event log
still records argument **counts** only, so raw values stay out of the uploaded-evidence log.

```
ToolEval: gid=17 tool=calculate ok=false native=true args={"expression":"4823743 times 184"}
```

## Running it

Device connected, model provisioned and selected in the app settings (or via
`shared_prefs/voxedge_settings.xml` `llm_model_id`). The harness force-stops, relaunches, waits for
`LLM ready`, then drives the golden set headlessly:

```bash
python tools/llm/eval_tools.py --label gemma4               # safe set (no side effects)
python tools/llm/eval_tools.py --label qwen3 --include-side-effects   # also timers/alarms/etc.
```

It writes a markdown report to `output/llm_eval/` (gitignored) with a summary plus a per-case table
(expected vs called tool, arg score, the spoken reply). Flip the backend and re-run with a different
`--label` to compare. The golden set's structure is validated in CI by
`tests/test_tool_eval_set.py` (offline; no device).

## Baseline — 2026-06-20 (Galaxy Z Fold7 / SM8750, airplane mode, 16 safe cases)

| backend | tool selection | — EN | — KO | arg extraction | false positives |
|---|---|---|---|---|---|
| **Qwen3-4B (Genie, prompt-convention)** | **5/11 (45%)** | 5/6 (83%) | **0/5 (0%)** | **4/4 (100%)** | 0/5 |
| **Gemma 4 E2B (LiteRT, native FC)** | 0/11 (0%) | 0/6 | 0/5 | n/a | 0/5 |

Findings (honest, reproducible):

- **Genie ≫ Gemma at tool calling.** The prompt-convention `[TOOL_CALL]` loop with few-shot
  exemplars fires reliably in English; Gemma's native function calling almost never volunteers a
  tool for natural phrasing — it answers inline instead, often **wrongly** (`18 plus 47` → "six
  hundred sixty-five"; a hallucinated battery percentage).
- **Genie's argument extraction is exact when it calls** (65, 13,104, 346,548,976 all correct);
  Gemma, when forced, **mangles the operands** (`48239 × 7184` → `"4823743 times 184"`, which then
  fails to evaluate).
- **Korean tool calling is the glaring gap.** Genie understands the Korean request but answers
  inline instead of calling the tool (`1234 더하기 5678` → "7912", wrong; "기억해 줘" acknowledged but
  never persisted). EN 83% vs KO 0% is the single most actionable number here.
- **No false positives** on either backend — chat prompts never spuriously call a tool.

### Tracked follow-ups this surfaced

- **Korean tool elicitation (both backends).** The `STYLE_DEMO_KO` few-shot isn't strong enough to
  trigger the tool convention in Korean. Candidate fix: Korean tool-call exemplars + a Korean tool
  preamble; re-measure against this baseline.
- **Gemma native-FC argument fidelity.** Tighten the OpenAPI declarations (typed/example args) and
  add a numeric-argument sanity re-prompt; measure the arg-extraction column.
