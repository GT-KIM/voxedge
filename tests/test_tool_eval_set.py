"""Validates the tool-calling golden set (tools/llm/tool_eval_set.json) so a malformed entry is
caught in CI, not on the device. Pure/offline — does NOT run the on-device harness."""

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GOLDEN = ROOT / "tools" / "llm" / "tool_eval_set.json"
DEVICE_TOOLS = (
    ROOT / "apps" / "android" / "app" / "src" / "main" / "kotlin"
    / "com" / "conversationalai" / "agent" / "devicetools" / "DeviceTools.kt"
)

# The argument names each tool accepts (mirrors DeviceTools.kt ToolSpec params). The test also
# asserts every tool NAME here actually exists in DeviceTools.kt, so a rename can't silently rot.
TOOL_PARAMS = {
    "get_datetime": set(),
    "battery_status": set(),
    "flashlight": {"state"},
    "calculate": {"expression"},
    "set_timer": {"minutes", "label"},
    "set_alarm": {"hour", "minute", "label"},
    "remember_fact": {"key", "value"},
    "recall_facts": {"query"},
    "forget_fact": {"key"},
    "create_calendar_event": {"title", "hour", "minute", "duration_minutes", "location"},
    "dial_number": {"number"},
    "send_sms": {"number", "message"},
    "navigate": {"destination"},
}
VALID_RULES = {"int", "digits", "expr", "contains"}


class ToolEvalSetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.spec = json.loads(GOLDEN.read_text(encoding="utf-8"))
        cls.cases = cls.spec["cases"]
        cls.device_tools = DEVICE_TOOLS.read_text(encoding="utf-8")

    def test_known_tools_exist_in_source(self):
        # Guard against the param map drifting from the actual tool registry.
        for name in TOOL_PARAMS:
            self.assertIn(f'name = "{name}"', self.device_tools, f"{name} missing from DeviceTools.kt")

    def test_case_ids_are_unique(self):
        ids = [c["id"] for c in self.cases]
        self.assertEqual(len(ids), len(set(ids)), "duplicate case ids")

    def test_match_rules_are_valid(self):
        for key, rule in self.spec.get("match_rules", {}).items():
            self.assertIn(rule, VALID_RULES, f"bad match rule '{rule}' for '{key}'")

    def test_every_case_is_well_formed(self):
        for c in self.cases:
            cid = c.get("id", "?")
            self.assertTrue(c.get("text"), f"{cid}: missing text")
            self.assertIn(c.get("lang"), {"ko", "en"}, f"{cid}: lang must be ko|en")
            self.assertIsInstance(c.get("expect_args", {}), dict, f"{cid}: expect_args must be a dict")
            expect = c.get("expect_tool")  # None = chat (must call no tool)
            if expect is None:
                self.assertEqual(c.get("expect_args", {}), {}, f"{cid}: chat case can't expect args")
                continue
            self.assertIn(expect, TOOL_PARAMS, f"{cid}: unknown expect_tool '{expect}'")
            for key in c.get("expect_args", {}):
                self.assertIn(key, TOOL_PARAMS[expect], f"{cid}: '{key}' is not a param of {expect}")

    def test_has_both_languages_and_chat_negatives(self):
        langs = {c["lang"] for c in self.cases}
        self.assertEqual(langs, {"ko", "en"}, "golden set must exercise both languages")
        chat = [c for c in self.cases if c.get("expect_tool") is None]
        tool = [c for c in self.cases if c.get("expect_tool")]
        self.assertGreaterEqual(len(chat), 3, "need chat negatives to measure false positives")
        self.assertGreaterEqual(len(tool), 8, "need a meaningful number of tool cases")


if __name__ == "__main__":
    unittest.main()
