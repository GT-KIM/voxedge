"""Sustained on-device run: loop typed turns for N minutes while sampling memory and thermal state.

Companion to eval_turns.py (single pass over the prompt battery). This one answers the questions
the measurement table needs per backend: decode throughput over time, process RSS/PSS, and whether
the device throttles under continuous use. Everything comes from the device: the app's JSONL event
log (`llm.generate_end`, `turn.end`), `dumpsys meminfo`, `dumpsys thermalservice`, and
`dumpsys battery` (temperature). Writes a markdown report to output/llm_eval/ (gitignored).

    python tools/llm/sustained_run.py --label gemma4 --minutes 10

Switch the backend first (settings sheet, or `llm_model_id` in shared_prefs/voxedge_settings.xml)
and provision the model. Airplane mode is the caller's responsibility; the report records what
`dumpsys connectivity` says about it.

The report also shows what the app's thermal/degrade policy did during the run (`runtime.thermal`
status changes, `runtime.degrade` level changes with the flow steps and response cap in force,
`turn.paused`) and the TTS flow steps each turn actually used. To watch the policy work instead of
stopping ahead of it, raise the guards: `--stop-status 3 --stop-skin-c 44`.
"""

import argparse
import datetime as dt
import json
import re
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from eval_turns import (  # noqa: E402
    APP, COMPONENT, adb, shell, start_turn, wait_for_logcat, extract_reply, pull_events, device_now_ms,
)

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PROMPTS = ROOT / "tools" / "llm" / "eval_prompts.json"
DEFAULT_OUT = ROOT / "output" / "llm_eval"


def sample_memory() -> dict:
    """TOTAL PSS / RSS (kB) for the app process from dumpsys meminfo."""
    out = shell(f"dumpsys meminfo {APP}", timeout=60)
    pss = re.search(r"TOTAL PSS:\s+(\d+)", out)
    rss = re.search(r"TOTAL RSS:\s+(\d+)", out)
    return {
        "pss_mb": round(int(pss.group(1)) / 1024, 1) if pss else None,
        "rss_mb": round(int(rss.group(1)) / 1024, 1) if rss else None,
    }


def sample_thermal() -> dict:
    """Thermal status level plus the CURRENT HAL sensor readings (AP, SKIN, BAT, CP ...).

    `dumpsys thermalservice` prints a "Cached temperatures" block first (stale; on the Fold7 it
    reports a constant AP 53.7) and then "Current temperatures from HAL" - only the latter is used.
    SKIN is the sensor the OS throttles on (thresholds 38/40/42/45 C on this device)."""
    battery = shell("dumpsys battery")
    temp = re.search(r"temperature:\s*(\d+)", battery)
    therm = shell("dumpsys thermalservice", timeout=60)
    status = re.search(r"Thermal Status:\s*(\d+)", therm)
    current = therm.split("Current temperatures from HAL:", 1)[-1].split("Current cooling devices", 1)[0]
    temps = {}
    for v, _t, name, _st in re.findall(r"Temperature\{mValue=([\d.]+), mType=(\d+), mName=([^,]+), mStatus=(\d+)\}", current):
        temps[name.strip()] = float(v)
    hottest = max(temps.items(), key=lambda kv: kv[1], default=None)
    return {
        "battery_c": int(temp.group(1)) / 10 if temp else None,
        "thermal_status": int(status.group(1)) if status else None,
        "ap_c": temps.get("AP"),
        "skin_c": temps.get("SKIN"),
        "cp_c": temps.get("CP"),
        "hottest_c": hottest[1] if hottest else None,
        "hottest_sensor": hottest[0] if hottest else None,
    }


def airplane_mode() -> str:
    out = shell("settings get global airplane_mode_on").strip()
    return {"1": "on", "0": "off"}.get(out, out or "unknown")


def fmt(v, suffix="") -> str:
    return "-" if v is None else f"{v}{suffix}"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--prompts", type=Path, default=DEFAULT_PROMPTS)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--label", default="run")
    ap.add_argument("--minutes", type=float, default=10.0, help="wall-clock duration of the loop")
    ap.add_argument("--turn-timeout", type=int, default=120)
    ap.add_argument("--settle", type=float, default=1.0, help="pause between turns (seconds)")
    ap.add_argument("--sample-every", type=int, default=1, help="sample mem/thermal every N turns")
    ap.add_argument("--stop-skin-c", type=float, default=42.0,
                    help="stop the loop when the SKIN sensor reaches this (42 C = the OS 'severe' step here)")
    ap.add_argument("--stop-status", type=int, default=2,
                    help="stop the loop when thermal status reaches this level (2 = MODERATE)")
    args = ap.parse_args()
    # Replies contain non-ASCII (Korean, dashes); a redirected stdout on Windows defaults to cp949.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

    if "device" not in adb("devices"):
        print("no adb device connected", file=sys.stderr)
        return 2

    battery = json.loads(args.prompts.read_text(encoding="utf-8"))["prompts"]
    prompts = [p for p in battery if not p.get("side_effect") and not p.get("expect_tool")]
    if not prompts:
        print("no chat prompts in the battery", file=sys.stderr)
        return 2

    shell(f"am force-stop {APP}")
    time.sleep(1)
    start_ms = device_now_ms()
    adb("logcat", "-c")
    shell(f"am start -n {COMPONENT}")
    ready = wait_for_logcat(r"LLM ready: ", 120, "RuntimeInit:I")
    if not ready:
        print("engine did not become ready (logcat RuntimeInit)", file=sys.stderr)
        return 2
    engine = re.search(r"LLM ready: ([^\r\n]+)", ready).group(1).strip()
    mode = airplane_mode()
    print(f"engine: {engine}  airplane_mode={mode}", flush=True)

    samples = [{"t_s": 0, "turn": 0, **sample_memory(), **sample_thermal()}]
    print(f"baseline: {samples[-1]}", flush=True)

    turns = []
    stop_reason = "duration reached"
    t_start = time.time()
    deadline = t_start + args.minutes * 60
    gid = 0
    while time.time() < deadline:
        prompt = prompts[gid % len(prompts)]
        gid += 1
        adb("logcat", "-c")
        start_turn(prompt["text"])
        dump = wait_for_logcat(r"debug_typed_turn done", args.turn_timeout, "MainActivity:I")
        reply = extract_reply(dump) if dump else "(timeout)"
        turns.append({"gid": gid, "id": prompt["id"], "reply": reply, "t_s": round(time.time() - t_start)})
        print(f"[{gid} @ {turns[-1]['t_s']}s] {prompt['id']}: {reply[:50]!r}", flush=True)
        if gid % args.sample_every == 0:
            smp = {"t_s": round(time.time() - t_start), "turn": gid, **sample_memory(), **sample_thermal()}
            samples.append(smp)
            print(f"  sample: skin={smp['skin_c']} ap={smp['ap_c']} bat={smp['battery_c']} "
                  f"status={smp['thermal_status']} pss={smp['pss_mb']}", flush=True)
            # Thermal guard: stop before the OS throttles so the run stays a measurement, not a stress test.
            if (smp.get("skin_c") or 0) >= args.stop_skin_c:
                stop_reason = f"SKIN {smp['skin_c']} C reached the {args.stop_skin_c} C guard"
                print(f"STOP: {stop_reason}", flush=True)
                break
            if (smp.get("thermal_status") or 0) >= args.stop_status:
                stop_reason = f"thermal status {smp['thermal_status']} reached the guard ({args.stop_status})"
                print(f"STOP: {stop_reason}", flush=True)
                break
        time.sleep(args.settle)
    samples.append({"t_s": round(time.time() - t_start), "turn": gid, **sample_memory(), **sample_thermal()})

    # Join per-turn decode stats from the event log.
    events = [e for e in pull_events() if e.get("t_wall_ms", 0) >= start_ms - 5000]
    by_gid = {}
    for e in events:
        g = e.get("generation_id")
        if g is not None:
            by_gid.setdefault(g, []).append(e)
    for t in turns:
        evs = by_gid.get(t["gid"], [])
        end = next((e for e in evs if e["event"] == "turn.end"), {})
        gen = next((e for e in evs if e["event"] == "llm.generate_end"), {})
        audio = next((e for e in evs if e["event"] == "tts.audio_chunk"), {})
        t.update(
            ttft=end.get("ttft_ms"), first_pcm=end.get("first_pcm_ms"), total=end.get("total_ms"),
            chunks=gen.get("chunks"), chars=gen.get("chars"), decode_ms=gen.get("decode_ms"),
            chunks_per_s=gen.get("chunks_per_s"), result=end.get("llm_result", "-"),
            flow_steps=audio.get("flow_steps"),
        )

    # Thermal / degrade policy trace (events carry the device wall clock; start_ms is the same clock).
    def rel_s(e):
        return round((e.get("t_wall_ms", start_ms) - start_ms) / 1000)

    policy = []
    last_state = None
    for e in events:
        if e["event"] == "runtime.thermal":
            if e.get("os_thermal_state") != last_state:
                last_state = e.get("os_thermal_state")
                policy.append((rel_s(e), "os status", f"{last_state} (policy level {e.get('level')})"))
        elif e["event"] == "runtime.degrade":
            policy.append((rel_s(e), "degrade", f"level {e.get('level')}: {e.get('actions') or 'none'}; "
                                                 f"flow_steps {e.get('flow_steps')}, cap {e.get('max_response_tokens')}"))
        elif e["event"] == "turn.paused":
            policy.append((rel_s(e), "turn paused", f"{e.get('reason')} ({e.get('level')}, {e.get('source')})"))
    degrade_levels = [e.get("level") for e in events if e["event"] == "runtime.degrade"]
    paused = sum(1 for e in events if e["event"] == "turn.paused")

    def med(key):
        vals = [t[key] for t in turns if t.get(key) not in (None, 0, 0.0)]
        return round(statistics.median(vals), 1) if vals else None

    def third(key, which):
        n = len(turns)
        part = turns[: n // 3] if which == "first" else turns[-(n // 3) or -1:]
        vals = [t[key] for t in part if t.get(key) not in (None, 0, 0.0)]
        return round(statistics.median(vals), 1) if vals else None

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    args.out.mkdir(parents=True, exist_ok=True)
    report = args.out / f"sustained_{args.label}_{stamp}.md"
    peak_pss = max((s["pss_mb"] for s in samples if s.get("pss_mb") is not None), default=None)
    peak_rss = max((s["rss_mb"] for s in samples if s.get("rss_mb") is not None), default=None)
    lines = [
        f"# Sustained run - {args.label} ({stamp})",
        "",
        f"- engine: `{engine}`",
        f"- airplane mode (settings): {mode}",
        f"- duration: {turns[-1]['t_s'] if turns else 0} s, {len(turns)} turns, "
        f"{sum(1 for t in turns if t['result'] != 'OK')} non-OK; stopped: {stop_reason}",
        f"- TTFT median: {fmt(med('ttft'), ' ms')}",
        f"- first-PCM median: {fmt(med('first_pcm'), ' ms')}",
        f"- decode: median {fmt(med('chunks_per_s'))} chunks/s "
        f"(first third {fmt(third('chunks_per_s', 'first'))}, last third {fmt(third('chunks_per_s', 'last'))}); "
        f"median {fmt(med('chars'))} chars over {fmt(med('decode_ms'), ' ms')}",
        f"- process memory peak: PSS {fmt(peak_pss, ' MB')} / RSS {fmt(peak_rss, ' MB')}",
        f"- thermal (HAL current): SKIN {fmt(samples[0]['skin_c'], ' C')} -> {fmt(samples[-1]['skin_c'], ' C')} "
        f"(max {fmt(max((s['skin_c'] or 0) for s in samples), ' C')}); "
        f"AP {fmt(samples[0]['ap_c'], ' C')} -> {fmt(samples[-1]['ap_c'], ' C')} "
        f"(max {fmt(max((s['ap_c'] or 0) for s in samples), ' C')}); "
        f"battery {fmt(samples[0]['battery_c'], ' C')} -> {fmt(samples[-1]['battery_c'], ' C')}; "
        f"max thermal status {max((s['thermal_status'] or 0) for s in samples)}",
        f"- degrade policy: {len(degrade_levels)} level change(s)"
        + (f" ({' -> '.join(degrade_levels)})" if degrade_levels else "")
        + f", {paused} turn(s) paused; flow steps used: "
        + ", ".join(f"K={k}: {n}" for k, n in sorted(
            ((k, sum(1 for t in turns if t.get("flow_steps") == k)) for k in {t.get("flow_steps") for t in turns}),
            key=lambda kv: (kv[0] is None, kv[0]))),
        "",
        "A 'chunk' is one streamed LLM callback: one token on LiteRT-LM, possibly several tokens on",
        "Genie. Compare chunks/s across runs of the same backend, not across backends.",
        "",
        "## Samples",
        "",
        "| t (s) | turn | PSS MB | RSS MB | SKIN C | AP C | CP C | battery C | status |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for s in samples:
        lines.append(
            f"| {s['t_s']} | {s['turn']} | {fmt(s['pss_mb'])} | {fmt(s['rss_mb'])} | {fmt(s['skin_c'])} "
            f"| {fmt(s['ap_c'])} | {fmt(s['cp_c'])} | {fmt(s['battery_c'])} | {fmt(s['thermal_status'])} |"
        )
    lines += ["", "## Thermal policy trace", ""]
    if policy:
        lines += ["| t (s) | what | detail |", "|---|---|---|"]
        lines += [f"| {t} | {what} | {detail} |" for t, what, detail in policy]
    else:
        lines.append("No `runtime.thermal` / `runtime.degrade` / `turn.paused` events during the run.")
    lines += [
        "",
        "## Turns",
        "",
        "| # | t (s) | id | ttft | first_pcm | total | chunks | chars | decode ms | chunks/s | K | result | reply |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for t in turns:
        reply = t["reply"].replace("|", "\\|").replace("\n", " ")[:80]
        cps = t.get("chunks_per_s")
        lines.append(
            f"| {t['gid']} | {t['t_s']} | {t['id']} | {fmt(t.get('ttft'))} | {fmt(t.get('first_pcm'))} "
            f"| {fmt(t.get('total'))} | {fmt(t.get('chunks'))} | {fmt(t.get('chars'))} | {fmt(t.get('decode_ms'))} "
            f"| {round(cps, 1) if cps else '-'} | {fmt(t.get('flow_steps'))} | {t['result']} | {reply} |"
        )
    report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\nreport: {report}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
