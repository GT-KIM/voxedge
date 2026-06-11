#!/usr/bin/env python3
"""On-device LLM turn-eval harness.

Drives the app's headless `debug_typed_turn` adb hook through a fixed prompt battery
(tools/llm/eval_prompts.json), then joins the spoken replies (logcat) with the runtime JSONL
event log (filesDir/runtime_logs/turn_events.jsonl) into a markdown report: per-turn
TTFT / first-PCM / total, session mode (full re-prefill vs warm KV continuation), llm_result,
tool calls + outcomes, and the reply text for side-by-side quality reading.

This is the measurement backbone for every LLM A/B: model swaps (Qwen3 Genie vs Gemma 4
LiteRT-LM), sampling changes, prompt changes, and tool-calling reliability.

Usage (host, device connected via adb, models provisioned):
    python tools/llm/eval_turns.py --label qwen3-temp06
    python tools/llm/eval_turns.py --label gemma4 --include-side-effects

The active model is whatever the app's persisted settings select; the report records the
engine line from logcat. Side-effecting tool prompts (timer/flashlight) are skipped unless
--include-side-effects is passed.
"""

import argparse
import datetime as dt
import json
import re
import subprocess
import sys
import time
from pathlib import Path

APP = "com.conversationalai.agent"
COMPONENT = f"{APP}/.ui.MainActivity"
ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PROMPTS = ROOT / "tools" / "llm" / "eval_prompts.json"
DEFAULT_OUT = ROOT / "output" / "llm_eval"


def adb(*args: str, timeout: int = 60) -> str:
    proc = subprocess.run(
        ["adb", *args], capture_output=True, timeout=timeout,
    )
    return proc.stdout.decode("utf-8", errors="replace")


def shell(cmd: str, timeout: int = 60) -> str:
    return adb("shell", cmd, timeout=timeout)


def start_turn(text: str) -> None:
    if text.isascii():
        arg = "'" + text.replace("'", "'\\''") + "'"
    else:
        # Non-ASCII survives Windows->adb->device most reliably as printf-escaped UTF-8 bytes.
        esc = "".join("\\x%02x" % b for b in text.encode("utf-8"))
        arg = '"$(printf \'%s\')"' % esc
    shell(f"am start -n {COMPONENT} --es debug_typed_turn {arg}")


def wait_for_logcat(pattern: str, timeout_s: int, tags: str) -> "str | None":
    """Poll `logcat -d` until [pattern] appears; returns the full dump, else None."""
    deadline = time.time() + timeout_s
    rx = re.compile(pattern)
    while time.time() < deadline:
        dump = adb("logcat", "-d", "-s", tags)
        if rx.search(dump):
            return dump
        time.sleep(2)
    return None


def extract_reply(dump: str) -> str:
    """Reply text from the logcat dump (between the reply marker and the done line)."""
    j = dump.find("debug_typed_turn done")
    marker = 'debug_typed_turn reply: "'
    i = dump.rfind(marker, 0, j if j >= 0 else len(dump))
    if i < 0:
        return ""
    seg = dump[i + len(marker):j if j >= 0 else len(dump)]
    k = seg.rfind('"')
    seg = seg[:k] if k >= 0 else seg
    # Multiline replies carry logcat line prefixes on continuation lines; strip them.
    return re.sub(r"\r?\n[\d\-: .]+\d+ +\d+ I MainActivity: ", "\n", seg).strip()


def pull_events() -> list:
    raw = shell(f"run-as {APP} cat files/runtime_logs/turn_events.jsonl", timeout=120)
    events = []
    for line in raw.splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    return events


def device_now_ms() -> int:
    out = shell("date +%s").strip()
    return int(out) * 1000


def fmt_ms(v) -> str:
    return f"{v}ms" if v is not None else "-"


def median(values):
    vals = sorted(v for v in values if v is not None)
    return vals[len(vals) // 2] if vals else None


def main() -> int:
    # Windows consoles default to a legacy codepage (cp949 here); model replies are Unicode.
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--prompts", type=Path, default=DEFAULT_PROMPTS)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--label", default="run", help="short tag for the report filename / header")
    ap.add_argument("--include-side-effects", action="store_true",
                    help="also run prompts that fire timers/alarms/flashlight on the device")
    ap.add_argument("--turn-timeout", type=int, default=120)
    ap.add_argument("--settle", type=float, default=3.0, help="pause between turns (seconds)")
    args = ap.parse_args()

    if "device" not in adb("devices"):
        print("no adb device connected", file=sys.stderr)
        return 2

    battery = json.loads(args.prompts.read_text(encoding="utf-8"))["prompts"]
    prompts = [p for p in battery if args.include_side_effects or not p.get("side_effect")]

    # Fresh process so generation ids start at 1 and map 1:1 onto the prompt order.
    shell(f"am force-stop {APP}")
    time.sleep(1)
    start_ms = device_now_ms()
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
    for i, prompt in enumerate(prompts, start=1):
        adb("logcat", "-c")
        start_turn(prompt["text"])
        dump = wait_for_logcat(r"debug_typed_turn done", args.turn_timeout, "MainActivity:I")
        reply = extract_reply(dump) if dump else "(timeout)"
        rows.append({"prompt": prompt, "gid": i, "reply": reply})
        print(f"[{i}/{len(prompts)}] {prompt['id']}: {reply[:60]!r}", flush=True)
        time.sleep(args.settle)

    # Join with the runtime event log (filter to this run by wall clock).
    events = [e for e in pull_events() if e.get("t_wall_ms", 0) >= start_ms - 5000]
    by_gid = {}
    for e in events:
        gid = e.get("generation_id")
        if gid is not None:
            by_gid.setdefault(gid, []).append(e)

    for row in rows:
        evs = by_gid.get(row["gid"], [])
        end = next((e for e in evs if e["event"] == "turn.end"), {})
        asm = next((e for e in evs if e["event"] == "prompt.assembled"), {})
        calls = [e for e in evs if e["event"] == "tool.call"]
        results = [e for e in evs if e["event"] == "tool.result"]
        row.update(
            ttft=end.get("ttft_ms"), first_pcm=end.get("first_pcm_ms"), total=end.get("total_ms"),
            llm_result=end.get("llm_result", "-"), session_mode=asm.get("session_mode", "-"),
            occupancy=asm.get("context_occupancy_pct"),
            tools=[(c.get("tool"), r.get("ok")) for c, r in zip(calls, results)],
        )
        expect = row["prompt"].get("expect_tool")
        row["tool_verdict"] = (
            "-" if not expect
            else "ok" if any(t == expect and ok for t, ok in row["tools"])
            else "called-failed" if any(t == expect for t, ok in row["tools"])
            else "not-called"
        )

    # Report.
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    args.out.mkdir(parents=True, exist_ok=True)
    report = args.out / f"eval_{args.label}_{stamp}.md"
    warm = [r for r in rows if r.get("session_mode") == "warm"]
    full = [r for r in rows if r.get("session_mode") == "full"]
    tool_rows = [r for r in rows if r["prompt"].get("expect_tool")]
    tool_ok = sum(1 for r in tool_rows if r["tool_verdict"] == "ok")

    lines = [
        f"# LLM turn eval — {args.label} ({stamp})",
        "",
        f"- engine: `{engine}`",
        f"- prompts: {len(rows)} ({len(tool_rows)} tool tasks; side-effects "
        f"{'included' if args.include_side_effects else 'skipped'})",
        f"- TTFT median: full {fmt_ms(median(r['ttft'] for r in full))} / "
        f"warm {fmt_ms(median(r['ttft'] for r in warm))}",
        f"- first-PCM median: full {fmt_ms(median(r['first_pcm'] for r in full))} / "
        f"warm {fmt_ms(median(r['first_pcm'] for r in warm))}",
        f"- tool success: {tool_ok}/{len(tool_rows)}" if tool_rows else "- tool success: n/a",
        "",
        "| id | mode | ttft | first_pcm | total | result | tool | reply |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        reply = r["reply"].replace("|", "\\|").replace("\n", " ")
        tools = ",".join(f"{t}:{'ok' if ok else 'fail'}" for t, ok in r["tools"]) or "-"
        lines.append(
            f"| {r['prompt']['id']} | {r.get('session_mode')} | {fmt_ms(r.get('ttft'))} "
            f"| {fmt_ms(r.get('first_pcm'))} | {fmt_ms(r.get('total'))} | {r.get('llm_result')} "
            f"| {tools} ({r['tool_verdict']}) | {reply} |"
        )
    report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\nreport: {report}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
