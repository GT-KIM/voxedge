"""Minimal host-side MCP client for the app's external endpoint (newline-delimited JSON-RPC 2.0).

The app exposes its MCP servers on Android abstract local sockets when "External MCP endpoint" is
switched on in the diagnostics panel. Reach them from the host through adb port forwarding:

    adb forward tcp:7777 localabstract:voxedge-mcp-tools
    python tools/mcp/mcp_client.py --port 7777 tools/list
    python tools/mcp/mcp_client.py --port 7777 tools/call get_datetime
    python tools/mcp/mcp_client.py --port 7777 tools/call calculate expression="18+47"

    adb forward tcp:7778 localabstract:voxedge-mcp-tts
    python tools/mcp/mcp_client.py --port 7778 tools/call speak text="Hello there." language=en --save out.wav

    adb forward tcp:7779 localabstract:voxedge-mcp-asr
    python tools/mcp/mcp_client.py --port 7779 tools/call transcribe --audio ko.wav language=ko engine=owned

`--save` writes the `audio` content block (WAV) of a speak result to a file. `--audio` sends a
16-bit PCM WAV file inline as the `audio_wav` argument (base64) of a transcribe call. The client
always runs the `initialize` handshake first, exactly like the in-app client.
"""

import argparse
import base64
import json
import socket
import sys

PROTOCOL_VERSION = "2025-06-18"


class McpLineClient:
    def __init__(self, host: str, port: int, timeout: float = 30.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.reader = self.sock.makefile("r", encoding="utf-8")
        self.writer = self.sock.makefile("w", encoding="utf-8")
        self.next_id = 1

    def notify(self, method: str, params=None):
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        self.writer.write(json.dumps(msg, ensure_ascii=False) + "\n")
        self.writer.flush()

    def request(self, method: str, params=None):
        rid = self.next_id
        self.next_id += 1
        msg = {"jsonrpc": "2.0", "id": rid, "method": method, "params": params or {}}
        self.writer.write(json.dumps(msg, ensure_ascii=False) + "\n")
        self.writer.flush()
        while True:
            line = self.reader.readline()
            if not line:
                raise ConnectionError("endpoint closed the connection")
            resp = json.loads(line)
            if resp.get("id") == rid:
                if "error" in resp:
                    raise RuntimeError(f"JSON-RPC error {resp['error'].get('code')}: {resp['error'].get('message')} "
                                       f"{resp['error'].get('data', '')}")
                return resp.get("result", {})

    def initialize(self):
        result = self.request("initialize", {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": {"name": "voxedge-host-client", "version": "1"},
        })
        self.notify("notifications/initialized")
        return result

    def close(self):
        self.sock.close()


def parse_args_kv(items):
    args = {}
    for item in items:
        if "=" not in item:
            raise SystemExit(f"argument must be key=value: {item}")
        k, v = item.split("=", 1)
        args[k] = v
    return args


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=True, help="local port forwarded with `adb forward`")
    ap.add_argument("method", choices=["initialize", "ping", "tools/list", "tools/call"])
    ap.add_argument("tool", nargs="?", help="tool name for tools/call")
    ap.add_argument("args", nargs="*", help="tool arguments as key=value")
    ap.add_argument("--save", help="write the audio content block of the result to this WAV file")
    ap.add_argument("--audio", help="send this 16-bit PCM WAV file inline as the audio_wav argument")
    a = ap.parse_args()

    client = McpLineClient(a.host, a.port)
    try:
        init = client.initialize()
        print(f"server: {init.get('serverInfo')} protocol {init.get('protocolVersion')}")
        if a.method == "initialize":
            return 0
        if a.method == "ping":
            client.request("ping")
            print("pong")
            return 0
        if a.method == "tools/list":
            for t in client.request("tools/list").get("tools", []):
                props = ", ".join(t.get("inputSchema", {}).get("properties", {}).keys())
                print(f"- {t['name']}({props}): {t.get('description', '')}")
            return 0
        if not a.tool:
            raise SystemExit("tools/call needs a tool name")
        arguments = parse_args_kv(a.args)
        if a.audio:
            with open(a.audio, "rb") as f:
                arguments["audio_wav"] = base64.b64encode(f.read()).decode("ascii")
        result = client.request("tools/call", {"name": a.tool, "arguments": arguments})
        print(f"isError: {result.get('isError')}")
        for block in result.get("content", []):
            if block.get("type") == "text":
                print(f"text: {block.get('text')}")
            elif block.get("type") == "audio":
                data = base64.b64decode(block.get("data", ""))
                print(f"audio: {len(data)} bytes {block.get('mimeType')}")
                if a.save:
                    with open(a.save, "wb") as f:
                        f.write(data)
                    print(f"saved: {a.save}")
        if result.get("structuredContent") is not None:
            print(f"structuredContent: {json.dumps(result['structuredContent'], ensure_ascii=False)}")
        return 0 if not result.get("isError") else 1
    finally:
        client.close()


if __name__ == "__main__":
    sys.exit(main())
