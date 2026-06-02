import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs" / "design" / "cross_platform_frontend_backend_plan.md"
ANDROID_UI = ROOT / "apps" / "android" / "app" / "src" / "main" / "kotlin" / "com" / "conversationalai" / "agent" / "ui"
ANDROID_CORE = ROOT / "apps" / "android" / "app" / "src" / "main" / "kotlin" / "com" / "conversationalai" / "agent" / "core"
IOS = ROOT / "apps" / "ios"
SCHEMA = ROOT / "shared" / "mcp" / "conversation_events.schema.json"


def normalize(name: str) -> str:
    return re.sub(r"[^a-z0-9]", "", name.lower())


class CrossPlatformFrontendBackendTest(unittest.TestCase):
    def test_plan_doc_records_native_mirror_and_in_process_bridge(self):
        text = DOC.read_text(encoding="utf-8")

        for required in [
            "Native Mirror",
            "in-process typed event/action bridge",
            "Do not add a local",
            "ConversationUiState",
            "ConversationAction",
            "ConversationRoute",
            "ConversationStore",
            "shared/mcp/conversation_events.schema.json",
            "shared/config/config.schema.json",
            "shared/prompts/base_system_prompt.md",
        ]:
            self.assertIn(required, text)

    def test_speech_loop_state_names_match_android_ios_contracts(self):
        android = (ANDROID_UI / "ConversationUiState.kt").read_text(encoding="utf-8")
        ios = (IOS / "Core" / "ConversationUiState.swift").read_text(encoding="utf-8")

        android_enum = re.search(r"enum class SpeechLoopUiState \{(.*?)\n\}", android, re.S)
        ios_enum = re.search(r"enum SpeechLoopUiState:.*?\{(.*?)\n\}", ios, re.S)
        self.assertIsNotNone(android_enum)
        self.assertIsNotNone(ios_enum)

        android_states = {
            normalize(line.strip().strip(",;"))
            for line in android_enum.group(1).splitlines()
            if line.strip().isupper()
        }
        ios_states = {
            normalize(match)
            for match in re.findall(r"case\s+([a-zA-Z0-9_]+)", ios_enum.group(1))
        }
        self.assertEqual(android_states, ios_states)

    def test_action_names_match_android_ios_contracts(self):
        android = (ANDROID_UI / "ConversationAction.kt").read_text(encoding="utf-8")
        ios = (IOS / "Core" / "ConversationAction.swift").read_text(encoding="utf-8")

        android_actions = {
            normalize(match)
            for match in re.findall(r"(?:data class|data object)\s+([A-Za-z0-9_]+)", android)
        }
        ios_actions = {
            normalize(match)
            for match in re.findall(r"case\s+([a-zA-Z0-9_]+)", ios)
        }
        self.assertEqual(android_actions, ios_actions)

    def test_readiness_kinds_match_android_ios_contracts(self):
        android = (ANDROID_UI / "ConversationUiState.kt").read_text(encoding="utf-8")
        ios = (IOS / "Core" / "ConversationUiState.swift").read_text(encoding="utf-8")

        android_enum = re.search(r"enum class RuntimeReadinessKind \{(.*?)\n\}", android, re.S)
        ios_enum = re.search(r"enum RuntimeReadinessKind:.*?\{(.*?)\n\}", ios, re.S)
        self.assertIsNotNone(android_enum)
        self.assertIsNotNone(ios_enum)

        android_kinds = {
            normalize(line.strip().strip(","))
            for line in android_enum.group(1).splitlines()
            if line.strip().isupper() or "_" in line
        }
        ios_kinds = {
            normalize(match)
            for match in re.findall(r"case\s+([a-zA-Z0-9_]+)", ios_enum.group(1))
        }
        self.assertEqual(android_kinds, ios_kinds)

    def test_mcp_event_wire_names_match_shared_schema(self):
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        expected = set(schema["$defs"]["Event"]["properties"]["type"]["enum"])
        android = (ANDROID_CORE / "McpEventType.kt").read_text(encoding="utf-8")
        ios = (IOS / "Core" / "McpEvents.swift").read_text(encoding="utf-8")

        android_events = set(re.findall(r'"([^"]+)"', android))
        ios_events = set(re.findall(r'=\s*"([^"]+)"', ios))

        self.assertEqual(android_events, expected)
        self.assertEqual(ios_events, expected)

    def test_runtime_bridge_does_not_introduce_local_network_api(self):
        source_roots = [ANDROID_UI, ANDROID_CORE, IOS]
        forbidden = ["localhost", "127.0.0.1", "WebSocket", "HttpServer", "http://", "https://"]

        for root in source_roots:
            for path in root.rglob("*"):
                if path.suffix.lower() not in {".kt", ".swift"}:
                    continue
                text = path.read_text(encoding="utf-8")
                for token in forbidden:
                    self.assertNotIn(token, text, f"{token} found in {path}")


if __name__ == "__main__":
    unittest.main()
