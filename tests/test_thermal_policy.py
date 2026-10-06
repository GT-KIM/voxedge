"""Thermal degrade policy: OS thermal status drives TTS flow steps, the response-token cap, and a
pause of new turns at critical, with hysteresis; levels/actions use the shared schema names; the
iOS mirror defines the same levels and caps. Offline; no device."""

import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AGENT = ROOT / "apps" / "android" / "app" / "src" / "main" / "kotlin" / "com" / "conversationalai" / "agent"
CORE = AGENT / "core"
SCHEMA = ROOT / "shared" / "mcp" / "conversation_events.schema.json"
IOS = ROOT / "apps" / "ios" / "Core" / "ThermalPolicy.swift"


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8")


class ThermalPolicyContractTest(unittest.TestCase):
    def test_levels_and_actions_use_the_shared_schema_names(self):
        schema = json.loads(read(SCHEMA))
        props = schema["$defs"]["Event"]["properties"]
        levels = set(props["thermal"]["properties"]["level"]["enum"])
        actions = set(props["degrade"]["properties"]["actions"]["items"]["enum"])
        kotlin = read(CORE / "ThermalPolicy.kt")
        self.assertEqual(set(re.findall(r'"(nominal|elevated|critical)"', kotlin)), levels)
        used = set(re.findall(r'"(reduce_flow_steps|shorten_response|pause_new_turns|lower_power_profile)"', kotlin))
        self.assertTrue(used <= actions, used - actions)
        self.assertEqual(used, {"reduce_flow_steps", "shorten_response", "pause_new_turns"})

    def test_android_monitor_uses_the_os_thermal_status(self):
        monitor = read(CORE / "ThermalMonitor.kt")
        self.assertIn("addThermalStatusListener", monitor)
        self.assertIn("currentThermalStatus", monitor)
        self.assertIn("getThermalHeadroom", monitor)
        main = read(AGENT / "ui" / "MainActivity.kt")
        self.assertIn("ThermalMonitor(this)", main)
        self.assertIn("controller.onThermalStatus(status, headroom)", main)

    def test_controller_applies_caps_and_pauses_every_turn_entry(self):
        ctrl = read(CORE / "ConversationController.kt")
        self.assertIn("flowSteps = { thermal.flowSteps(ttsFlowSteps) }", ctrl)
        self.assertNotIn("flowSteps = { ttsFlowSteps }", ctrl)
        self.assertIn("thermal.maxResponseTokens(maxResponseTokens)", ctrl)
        self.assertIn('pausedForThermal("hands_free")', ctrl)
        self.assertIn('pausedForThermal("typed")', ctrl)
        self.assertIn("thermal.pauseNewTurns) return", ctrl)   # speculative entry
        for event in ('"runtime.thermal"', '"runtime.degrade"', '"turn.paused"'):
            self.assertIn(event, ctrl)
        settings = read(AGENT / "ui" / "SettingsController.kt")
        self.assertIn("controller.setMaxResponseTokens(", settings)
        self.assertNotIn("llm.setMaxResponseTokens(maxTokens)", settings)

    def test_ios_mirror_matches_levels_and_caps(self):
        swift = read(IOS)
        kotlin = read(CORE / "ThermalPolicy.kt")
        for wire in ("nominal", "elevated", "critical"):
            self.assertIn(f'"{wire}"', swift)
        k_flow = re.search(r"DEGRADED_FLOW_STEPS = (\d+)", kotlin).group(1)
        k_min = re.search(r"MIN_RESPONSE_TOKENS = (\d+)", kotlin).group(1)
        self.assertIn(f"degradedFlowSteps = {k_flow}", swift)
        self.assertIn(f"minResponseTokens = {k_min}", swift)
        self.assertIn("ProcessInfo.ThermalState", swift)


if __name__ == "__main__":
    unittest.main()
