#!/usr/bin/env python3
"""On-device TOOL-CALLING eval harness.

Drives the app's headless `debug_typed_turn` hook through a tool golden set
(tools/llm/tool_eval_set.json) and scores three things for the *currently selected* backend
(Qwen3 Genie prompt-convention OR Gemma 4 LiteRT native function calling):

  1. tool SELECTION   — did the model call the expected tool (and, for chat prompts, NO tool)?
  2. argument EXTRACTION — did it pull the right argument values (numbers, phone digits, expressions)?
  3. false positives  — chat prompts that wrongly trigger a tool.

It reads the actual call arguments from the dedicated `ToolEval` logcat line (emitted for BOTH
backends since the A1 observability unification — Gemma's native calls were previously invisible).
The persistent JSONL event log still records arg counts only, so raw values stay out of it.

Usage (host, device connected, model provisioned + selected in the app settings):
    python tools/llm/eval_tools.py --label gemma4
    python tools/llm/eval_tools.py --label qwen3 --include-side-effects

To compare backends, flip the model in the app (or shared_prefs llm_model_id), relaunch, and run
again with a different --label; diff the two reports.
"""

import argparse
import datetime as dt
import json
import re
import sys
import time
from pathlib import Path

# Reuse the proven adb plumbing (quoting, logcat polling, readiness) from the turn-eval harness.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from eval_turns import (  # noqa: E402
    adb, shell, start_turn, wait_for_logcat, extract_reply, device_now_ms, APP, COMPONENT,
)

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SET = ROOT / "tools" / "llm" / "tool_eval_set.json"
DEFAULT_OUT = ROOT / "output" / "llm_eval"

EVAL_RX = re.compile(
    r"ToolEval: gid=(\d+) tool=(\S+) ok=(\w+) native=(\w+) args=(\{.*\})"
)


def parse_tool_evals(dump: str) -> list:
    """Pull (tool, ok, native, args) tuples from the ToolEval logcat lines in [dump]."""
    out = []
    for m in EVAL_RX.finditer(dump):
        tool, ok, native, args_json = m.group(2), m.group(3), m.group(4), m.group(5)
        try:
            args = json.loads(args_json)
        except json.JSONDecodeError:
            args = {}
        out.append({"tool": tool, "ok": ok == "true", "native": native == "true", "args": args})
    return out


def norm(s: str) -> str:
    return re.sub(r"\s+", "", str(s)).casefold()


def arg_matches(rule: str, expected: str, actual: str) -> bool:
    if actual is None:
        return False
    if rule == "int":
        try:
            return int(float(re.sub(r"[^\d.\-]", "", str(expected)) or "x")) == \
                   int(float(re.sub(r"[^\d.\-]", "", str(actual)) or "x"))
        except ValueError:
            return False
    if rule == "digits":
        return re.sub(r"\D", "", str(expected)) == re.sub(r"\D", "", str(actual))
    if rule == "expr":
        return norm(expected) == norm(actual)
    # default "contains": tolerant either-direction substring (loose keys/free text)
    e, a = norm(expected), norm(actual)
    return e in a or a in e


def score_case(case: dict, evals: list, rules: dict) -> dict:
    expected = case.get("expect_tool")
    called = [e["tool"] for e in evals]
    native = any(e["native"] for e in evals)

    if expected is None:
        # Chat prompt: success == no tool fired.
        return {"selection": not evals, "false_positive": bool(evals),
                "arg_total": 0, "arg_ok": 0, "called": called, "native": native}

    selection = expected in called
    arg_total = arg_ok = 0
    if selection:
        entry = next(e for e in evals if e["tool"] == expected)
        for key, exp_val in (case.get("expect_args") or {}).items():
            arg_total += 1
            rule = rules.get(key, rules.get("_default", "contains"))
            if arg_matches(rule, exp_val, entry["args"].get(key)):
                arg_ok += 1
    return {"selection": selection, "false_positive": False,
            "arg_total": arg_total, "arg_ok": arg_ok, "called": called, "native": native}


def pct(n: int, d: int) -> str:
    return f"{n}/{d} ({100 * n // d}%)" if d else "n/a"


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--set", type=Path, default=DEFAULT_SET, dest="golden")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--label", default="tools", help="short tag for the report filename / header")
    ap.add_argument("--include-side-effects", action="store_true",
                    help="also run prompts that fire timers/alarms/flashlight/dialer/maps")
    ap.add_argument("--turn-timeout", type=int, default=120)
    ap.add_argument("--settle", type=float, default=3.0)
    args = ap.parse_args()

    if "device" not in adb("devices"):
        print("no adb device connected", file=sys.stderr)
        return 2

    spec = json.loads(args.golden.read_text(encoding="utf-8"))
    rules = spec.get("match_rules", {})
    cases = [c for c in spec["cases"] if args.include_side_effects or not c.get("side_effect")]

    shell(f"am force-stop {APP}")
    time.sleep(1)
    adb("logcat", "-c")
    shell(f"am start -n {COMPONENT}")
    ready = wait_for_logcat(r"LLM ready: ", 90, "RuntimeInit:I")
    if not ready:
        print("engine did not become ready (logcat RuntimeInit)", file=sys.stderr)
        return 2
    m = re.search(r"LLM ready: ([^\r\n]+)", ready)
    engine = m.group(1).strip() if m else "unknown"
    print(f"engine: {engine}", flush=True)

    rows = []
    for i, case in enumerate(cases, start=1):
        adb("logcat", "-c")
        start_turn(case["text"])
        dump = wait_for_logcat(r"debug_typed_turn done", args.turn_timeout, "MainActivity:I ToolEval:I")
        evals = parse_tool_evals(dump) if dump else []
        reply = extract_reply(dump) if dump else "(timeout)"
        score = score_case(case, evals, rules)
        rows.append({"case": case, "score": score, "reply": reply})
        tag = "OK" if score["selection"] else "MISS"
        print(f"[{i}/{len(cases)}] {case['id']}: {tag} called={score['called'] or '-'}", flush=True)
        time.sleep(args.settle)

    # Aggregate.
    tool_cases = [r for r in rows if r["case"].get("expect_tool")]
    chat_cases = [r for r in rows if not r["case"].get("expect_tool")]
    sel_ok = sum(1 for r in tool_cases if r["score"]["selection"])
    fp = sum(1 for r in chat_cases if r["score"]["false_positive"])
    arg_total = sum(r["score"]["arg_total"] for r in rows)
    arg_ok = sum(r["score"]["arg_ok"] for r in rows)
    ko = [r for r in tool_cases if r["case"]["lang"] == "ko"]
    en = [r for r in tool_cases if r["case"]["lang"] == "en"]
    native = any(r["score"]["native"] for r in rows)

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    args.out.mkdir(parents=True, exist_ok=True)
    report = args.out / f"tooleval_{args.label}_{stamp}.md"
    lines = [
        f"# Tool-calling eval — {args.label} ({stamp})",
        "",
        f"- engine: `{engine}` (native function-calling: {native})",
        f"- cases: {len(rows)} ({len(tool_cases)} tool, {len(chat_cases)} chat; "
        f"side-effects {'included' if args.include_side_effects else 'skipped'})",
        f"- **tool selection**: {pct(sel_ok, len(tool_cases))} "
        f"(EN {pct(sum(1 for r in en if r['score']['selection']), len(en))}, "
        f"KO {pct(sum(1 for r in ko if r['score']['selection']), len(ko))})",
        f"- **argument extraction**: {pct(arg_ok, arg_total)} (over selected tool calls)",
        f"- **false-positive tool calls** (chat -> tool): {pct(fp, len(chat_cases))}",
        "",
        "| id | lang | expect | called | sel | args | reply |",
        "|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        c, s = r["case"], r["score"]
        called = ",".join(s["called"]) or "-"
        argcell = f"{s['arg_ok']}/{s['arg_total']}" if s["arg_total"] else "-"
        if c.get("expect_tool"):
            sel = "ok" if s["selection"] else "MISS"
        else:
            sel = "ok" if not s["false_positive"] else "FP"
        reply = r["reply"].replace("|", "\\|").replace("\n", " ")[:80]
        lines.append(
            f"| {c['id']} | {c['lang']} | {c.get('expect_tool') or '(none)'} "
            f"| {called} | {sel} | {argcell} | {reply} |"
        )
    report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\nselection {pct(sel_ok, len(tool_cases))} | args {pct(arg_ok, arg_total)} | "
          f"false-pos {pct(fp, len(chat_cases))}")
    print(f"report: {report}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
