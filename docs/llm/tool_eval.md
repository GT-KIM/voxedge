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

## Improvement — 2026-06-21 (tool-use policy split)

The baseline exposed the root cause of Gemma's 0%: the forceful "you MUST use the tool" directives
lived entirely in the prompt-convention `toolsModule`, which the controller passes as `emptyList()`
for native FC — so **Gemma got zero instruction to prefer tools**, only bare declarations, and
answered from its head. Fix: split the guidance into a backend-neutral `TOOL_POLICY` (when to use a
tool + Korean triggers, shown to BOTH backends) vs the `[TOOL_CALL]` syntax module (prompt-convention
only). `PromptAssembler.systemPrompt(..., nativeTools=)` routes them.

| backend | selection (before → after) | KO | false positives |
|---|---|---|---|
| **Gemma 4 E2B (LiteRT, native FC)** | **0/11 → 3/11 (+27pp)** | now calls KO time + battery | 0/5 (no over-calling) |
| **Qwen3-4B (Genie, prompt-convention)** | 45% → 45% (unchanged; ±1 case is sampling noise) | **still 0/5** | 0/5 |

What the measurement settled:
- The policy split is a **real win on the default backend** (Gemma) with **no new false positives** —
  the stricter directives didn't make chat prompts spuriously call tools.
- Gemma still skips `calculate` (it trusts its own math) and `remember_fact`; native FC weights its
  tool declarations over the system instruction for those.
- **Genie's Korean is structurally resistant.** A *verbatim* Korean exemplar (`지금 몇 시야?` →
  `[TOOL_CALL]`) still didn't make Genie call the tool for the identical Korean question — it answers
  inline regardless. Prompt exemplars can't move it; that exemplar was reverted (ineffective + it
  matched a test case).

### Tracked follow-ups this surfaced

- **Genie Korean tool elicitation (0/5, prompt-resistant).** Korean exemplars and triggers do NOT
  move it — Genie answers Korean inline even for a request identical to an in-prompt exemplar. The
  fix likely isn't prompt text: candidates are a decoding nudge (lower temperature / constrained tool
  grammar on Korean turns) or a lightweight intent classifier that forces the tool call. Measure each
  against this baseline.
- **Gemma `calculate`/`remember_fact` (still skipped).** The policy got Gemma calling clock/battery
  but not math/memory — native FC trusts its own arithmetic. Candidate: strengthen the OpenAPI
  declaration text for `calculate` (it's weighted more than the system instruction) and re-measure.
- **Gemma native-FC argument fidelity.** When it does call, it mangles operands. Tighten the OpenAPI
  declarations (typed/example args) and add a numeric-argument sanity re-prompt; measure the
  arg-extraction column.
- **Eval variance.** Single runs flip ±1 borderline case (sampling). Consider an `--repeat N` mode
  that averages, so small prompt changes are judged above the noise floor.
