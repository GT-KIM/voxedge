"""MCP boundary contract: fixtures are valid JSON-RPC 2.0 / MCP messages, and the Android and iOS
implementations agree with the shared profile (protocol revision, method names, server and tool
names). Offline; no device."""

import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MCP_DIR = ROOT / "shared" / "mcp"
FIXTURES = MCP_DIR / "fixtures"
README = MCP_DIR / "README.md"
SCHEMA = MCP_DIR / "conversation_events.schema.json"
ANDROID_MCP = (
    ROOT / "apps" / "android" / "app" / "src" / "main" / "kotlin" / "com" / "conversationalai" / "agent" / "core" / "mcp"
)
IOS_MCP = ROOT / "apps" / "ios" / "Core" / "Mcp.swift"

PROTOCOL_VERSION = "2025-06-18"
METHODS = {"initialize", "notifications/initialized", "ping", "tools/list", "tools/call"}
SERVERS = {"voxedge-device-tools", "voxedge-tts"}
SPEAK_TOOL = "speak"
ERROR_CODES = {-32700, -32600, -32601, -32602, -32603}


def load_fixtures():
    return {p.name: json.loads(p.read_text(encoding="utf-8")) for p in sorted(FIXTURES.glob("*.json"))}


class McpFixtureTest(unittest.TestCase):
    def test_every_fixture_is_a_json_rpc_2_message(self):
        fixtures = load_fixtures()
        self.assertGreaterEqual(len(fixtures), 10)
        for name, msg in fixtures.items():
            with self.subTest(fixture=name):
                self.assertEqual(msg.get("jsonrpc"), "2.0")
                is_request = "method" in msg and "id" in msg
                is_notification = "method" in msg and "id" not in msg
                is_response = "method" not in msg and "id" in msg
                self.assertTrue(is_request or is_notification or is_response, "neither request, notification nor response")
                if is_response:
                    self.assertTrue(("result" in msg) != ("error" in msg), "response needs exactly one of result/error")
                    if "error" in msg:
                        self.assertIn(msg["error"]["code"], ERROR_CODES)
                        self.assertIsInstance(msg["error"]["message"], str)
                if "method" in msg:
                    self.assertIn(msg["method"], METHODS)

    def test_fixtures_cover_every_profile_method(self):
        methods = {m["method"] for m in load_fixtures().values() if "method" in m}
        self.assertEqual(methods, METHODS)

    def test_initialize_handshake_carries_the_protocol_revision(self):
        fx = load_fixtures()
        self.assertEqual(fx["initialize.request.json"]["params"]["protocolVersion"], PROTOCOL_VERSION)
        result = fx["initialize.response.json"]["result"]
        self.assertEqual(result["protocolVersion"], PROTOCOL_VERSION)
        self.assertIn("tools", result["capabilities"])
        self.assertIn(result["serverInfo"]["name"], SERVERS)

    def test_tools_list_entries_have_json_schema_input(self):
        tools = load_fixtures()["tools_list.response.json"]["result"]["tools"]
        for tool in tools:
            self.assertEqual(set(tool) >= {"name", "description", "inputSchema"}, True)
            self.assertEqual(tool["inputSchema"]["type"], "object")
            for required in tool["inputSchema"].get("required", []):
                self.assertIn(required, tool["inputSchema"]["properties"])

    def test_tools_call_results_follow_mcp_content_shape(self):
        fx = load_fixtures()
        for name in ("tools_call.response.json", "tools_call.tool_error.response.json", "speak.response.json"):
            result = fx[name]["result"]
            with self.subTest(fixture=name):
                self.assertIsInstance(result["isError"], bool)
                self.assertTrue(result["content"])
                for block in result["content"]:
                    self.assertEqual(block["type"], "text")
                    self.assertIsInstance(block["text"], str)
        self.assertTrue(fx["tools_call.tool_error.response.json"]["result"]["isError"])
        unknown = fx["tools_call.unknown_tool.response.json"]["error"]
        self.assertEqual(unknown["code"], -32602)
        self.assertIsInstance(unknown["data"]["available"], list)

    def test_speak_call_keeps_audio_out_of_json(self):
        fx = load_fixtures()
        req = fx["speak.request.json"]["params"]
        self.assertEqual(req["name"], SPEAK_TOOL)
        self.assertEqual(set(req["arguments"]) >= {"text", "language", "clause_index", "chunk_id", "generation_id"}, True)
        sc = fx["speak.response.json"]["result"]["structuredContent"]
        self.assertEqual(set(sc) >= {"pcm_ref", "sample_rate", "num_samples"}, True)
        self.assertEqual(sc["sample_rate"], 44100)
        self.assertNotIn("pcm", sc)
        self.assertNotIn("audio", fx["speak.response.json"]["result"])


class McpPlatformParityTest(unittest.TestCase):
    def kotlin(self):
        return "\n".join(p.read_text(encoding="utf-8") for p in sorted(ANDROID_MCP.glob("*.kt")))

    def swift(self):
        return IOS_MCP.read_text(encoding="utf-8")

    def test_android_and_ios_pin_the_same_protocol_revision(self):
        self.assertIn(f'PROTOCOL_VERSION = "{PROTOCOL_VERSION}"', self.kotlin())
        self.assertIn(f'protocolVersion = "{PROTOCOL_VERSION}"', self.swift())

    def test_android_and_ios_define_every_profile_method(self):
        kotlin_methods = set(re.findall(r'const val METHOD_\w+ = "([^"]+)"', self.kotlin()))
        swift_methods = set(re.findall(r'static let method\w+ = "([^"]+)"', self.swift()))
        self.assertEqual(kotlin_methods, METHODS)
        self.assertEqual(swift_methods, METHODS)

    def test_server_and_tool_names_match_across_platforms(self):
        kotlin = self.kotlin()
        swift = self.swift()
        for server in SERVERS:
            self.assertIn(f'"{server}"', kotlin)
            self.assertIn(f'"{server}"', swift)
        self.assertIn(f'TOOL_SPEAK = "{SPEAK_TOOL}"', kotlin)
        self.assertIn(f'ttsSpeakTool = "{SPEAK_TOOL}"', swift)

    def test_android_server_handles_every_profile_method(self):
        server = (ANDROID_MCP / "Mcp.kt").read_text(encoding="utf-8")
        for const in ("METHOD_INITIALIZE", "METHOD_PING", "METHOD_TOOLS_LIST", "METHOD_TOOLS_CALL", "METHOD_INITIALIZED"):
            self.assertIn(f"Mcp.{const} ->", server) if const != "METHOD_INITIALIZED" else self.assertIn(
                f"msg.method == Mcp.{const}", server
            )

    def test_tool_registry_dispatch_goes_through_mcp(self):
        tools_kt = (ANDROID_MCP.parent / "tools" / "Tools.kt").read_text(encoding="utf-8")
        self.assertIn("fun dispatch(call: ToolCall): ToolResult = mcp.call(call)", tools_kt)
        runner = (ANDROID_MCP.parent / "SpeechTurnRunner.kt").read_text(encoding="utf-8")
        self.assertIn("ttsMcp.speak(", runner)
        self.assertNotIn("tts.synthesizeClause(", runner)


class McpExternalEndpointTest(unittest.TestCase):
    def test_external_endpoint_uses_local_sockets_not_network(self):
        src = (ANDROID_MCP / "McpExternalEndpoint.kt").read_text(encoding="utf-8")
        self.assertIn("LocalServerSocket", src)
        self.assertIn('TOOLS_SOCKET = "voxedge-mcp-tools"', src)
        self.assertIn('TTS_SOCKET = "voxedge-mcp-tts"', src)
        self.assertIn("server.handleText(line)", src)
        self.assertIn('"type" to "audio"', src)
        for forbidden in (" ServerSocket(", "InetAddress", "java.net", "127.0.0.1"):
            self.assertNotIn(forbidden, src)

    def test_endpoint_is_off_by_default_and_not_persisted(self):
        main = (ANDROID_MCP.parent.parent / "ui" / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("private var mcpEndpoints: List<McpExternalEndpoint> = emptyList()", main)
        store = (ANDROID_MCP.parent.parent / "ui" / "SettingsStore.kt").read_text(encoding="utf-8")
        self.assertNotIn("mcp", store.lower())
        route = (ANDROID_MCP.parent.parent / "ui" / "ConversationRoute.kt").read_text(encoding="utf-8")
        self.assertIn("var mcpEndpoint by remember { mutableStateOf(false) }", route)

    def test_host_client_speaks_the_same_profile(self):
        client = (ROOT / "tools" / "mcp" / "mcp_client.py").read_text(encoding="utf-8")
        self.assertIn(f'PROTOCOL_VERSION = "{PROTOCOL_VERSION}"', client)
        for method in ("initialize", "notifications/initialized", "tools/list", "tools/call"):
            self.assertIn(f'"{method}"', client)
        self.assertIn("localabstract:voxedge-mcp-tools", client)


class McpDocsTest(unittest.TestCase):
    def test_readme_states_mcp_is_the_boundary_and_lists_the_profile(self):
        text = README.read_text(encoding="utf-8")
        self.assertNotIn("NOT MCP", text)
        self.assertIn(PROTOCOL_VERSION, text)
        for method in METHODS:
            self.assertIn(f"`{method}`", text)
        for server in SERVERS:
            self.assertIn(server, text)
        self.assertIn("docs/design/mcp_boundary.md", text)
        self.assertIn("localabstract:voxedge-mcp-tools", text)

    def test_event_schema_lists_mcp_events(self):
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        events = set(schema["$defs"]["Event"]["properties"]["type"]["enum"])
        self.assertTrue({"mcp.request", "mcp.response"} <= events)
        self.assertNotIn("NOT MCP", json.dumps(schema))


if __name__ == "__main__":
    unittest.main()
