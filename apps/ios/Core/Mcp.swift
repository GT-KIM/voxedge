import Foundation

/// Model Context Protocol mirror of the Android `core/mcp` package: the same protocol revision,
/// method names, server names, and `speak` tool, so both platforms speak one MCP profile
/// (`shared/mcp/README.md`). JSON-RPC 2.0 over an in-process transport by default.
/// Build-ready; not yet compiled or run (no Mac build path).
enum Mcp {
    static let protocolVersion = "2025-06-18"

    static let methodInitialize = "initialize"
    static let methodInitialized = "notifications/initialized"
    static let methodPing = "ping"
    static let methodToolsList = "tools/list"
    static let methodToolsCall = "tools/call"

    static let deviceToolsServerName = "voxedge-device-tools"
    static let ttsServerName = "voxedge-tts"
    static let ttsSpeakTool = "speak"

    static let jsonRpcVersion = "2.0"
    static let parseError = -32700
    static let invalidRequest = -32600
    static let methodNotFound = -32601
    static let invalidParams = -32602
    static let internalError = -32603
}

struct McpToolDescriptor {
    let name: String
    let description: String
    let inputSchema: [String: Any]

    var json: [String: Any] {
        ["name": name, "description": description, "inputSchema": inputSchema]
    }
}

struct McpCallResult {
    let text: String
    var isError: Bool = false
    var structuredContent: [String: Any]? = nil

    var json: [String: Any] {
        var m: [String: Any] = [
            "content": [["type": "text", "text": text]],
            "isError": isError,
        ]
        if let sc = structuredContent { m["structuredContent"] = sc }
        return m
    }

    static func from(_ m: [String: Any]) -> McpCallResult {
        let blocks = (m["content"] as? [[String: Any]]) ?? []
        let text = blocks.compactMap { ($0["type"] as? String) == "text" ? $0["text"] as? String : nil }
            .joined(separator: "\n")
        return McpCallResult(
            text: text,
            isError: (m["isError"] as? Bool) ?? false,
            structuredContent: m["structuredContent"] as? [String: Any]
        )
    }
}

struct McpToolHandler {
    let descriptor: McpToolDescriptor
    let call: ([String: Any]) -> McpCallResult
}

struct McpError: Error {
    let code: Int
    let message: String
    let data: Any?
}

/// Bidirectional message pipe; `send` delivers one JSON text to the peer.
protocol McpTransport: AnyObject {
    var onMessage: ((String) -> Void)? { get set }
    func send(_ json: String)
}

final class InProcessTransport: McpTransport {
    weak var peer: InProcessTransport?
    var onMessage: ((String) -> Void)?

    func send(_ json: String) {
        peer?.onMessage?(json)
    }

    static func pair() -> (InProcessTransport, InProcessTransport) {
        let a = InProcessTransport()
        let b = InProcessTransport()
        a.peer = b
        b.peer = a
        return (a, b)
    }
}

/// Tools-capable MCP server (initialize / ping / tools/list / tools/call).
final class McpServer {
    let name: String
    let version: String
    private let tools: [String: McpToolHandler]
    private let transport: McpTransport
    private(set) var initialized = false

    init(name: String, version: String, tools: [McpToolHandler], transport: McpTransport) {
        self.name = name
        self.version = version
        self.tools = Dictionary(uniqueKeysWithValues: tools.map { ($0.descriptor.name, $0) })
        self.transport = transport
        transport.onMessage = { [weak self] text in self?.handleRaw(text) }
    }

    func handleRaw(_ text: String) {
        guard let data = text.data(using: .utf8),
              let any = try? JSONSerialization.jsonObject(with: data),
              let m = any as? [String: Any] else {
            reply(id: nil, error: McpError(code: Mcp.parseError, message: "parse error", data: nil))
            return
        }
        guard (m["jsonrpc"] as? String) == Mcp.jsonRpcVersion, let method = m["method"] as? String else {
            if m["method"] != nil || m["id"] == nil {
                reply(id: nil, error: McpError(code: Mcp.invalidRequest, message: "invalid request", data: nil))
            }
            return
        }
        let params = (m["params"] as? [String: Any]) ?? [:]
        guard let id = m["id"] else {
            if method == Mcp.methodInitialized { initialized = true }
            return
        }
        switch method {
        case Mcp.methodInitialize:
            reply(id: id, result: [
                "protocolVersion": Mcp.protocolVersion,
                "capabilities": ["tools": ["listChanged": false]],
                "serverInfo": ["name": name, "version": version],
            ])
        case Mcp.methodPing:
            reply(id: id, result: [:])
        case Mcp.methodToolsList:
            reply(id: id, result: ["tools": tools.keys.sorted().map { tools[$0]!.descriptor.json }])
        case Mcp.methodToolsCall:
            guard let toolName = params["name"] as? String else {
                reply(id: id, error: McpError(code: Mcp.invalidParams, message: "missing tool name", data: nil))
                return
            }
            guard let handler = tools[toolName] else {
                reply(id: id, error: McpError(code: Mcp.invalidParams, message: "unknown tool '\(toolName)'",
                                             data: ["available": tools.keys.sorted()]))
                return
            }
            let args = (params["arguments"] as? [String: Any]) ?? [:]
            reply(id: id, result: handler.call(args).json)
        default:
            reply(id: id, error: McpError(code: Mcp.methodNotFound, message: "unknown method '\(method)'", data: nil))
        }
    }

    private func reply(id: Any?, result: [String: Any]? = nil, error: McpError? = nil) {
        var m: [String: Any] = ["jsonrpc": Mcp.jsonRpcVersion, "id": id ?? NSNull()]
        if let e = error {
            var err: [String: Any] = ["code": e.code, "message": e.message]
            if let d = e.data { err["data"] = d }
            m["error"] = err
        } else {
            m["result"] = result ?? [:]
        }
        if let data = try? JSONSerialization.data(withJSONObject: m),
           let text = String(data: data, encoding: .utf8) {
            transport.send(text)
        }
    }
}

/// MCP client with a synchronous request API over an in-process transport.
final class McpClient {
    private let transport: McpTransport
    private var nextId = 1
    private var responses: [Int: [String: Any]] = [:]
    private(set) var serverInfo: [String: Any]?

    init(transport: McpTransport) {
        self.transport = transport
        transport.onMessage = { [weak self] text in self?.onRaw(text) }
    }

    private func onRaw(_ text: String) {
        guard let data = text.data(using: .utf8),
              let m = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let id = m["id"] as? Int else { return }
        responses[id] = m
    }

    func initialize() throws -> [String: Any] {
        let result = try request(Mcp.methodInitialize, params: [
            "protocolVersion": Mcp.protocolVersion,
            "capabilities": [:],
            "clientInfo": ["name": "voxedge", "version": "1"],
        ])
        notify(Mcp.methodInitialized)
        serverInfo = result["serverInfo"] as? [String: Any]
        return result
    }

    func listTools() throws -> [McpToolDescriptor] {
        let tools = (try request(Mcp.methodToolsList)["tools"] as? [[String: Any]]) ?? []
        return tools.map {
            McpToolDescriptor(
                name: ($0["name"] as? String) ?? "",
                description: ($0["description"] as? String) ?? "",
                inputSchema: ($0["inputSchema"] as? [String: Any]) ?? [:]
            )
        }
    }

    func callTool(_ name: String, arguments: [String: Any]) throws -> McpCallResult {
        McpCallResult.from(try request(Mcp.methodToolsCall, params: ["name": name, "arguments": arguments]))
    }

    func notify(_ method: String, params: [String: Any]? = nil) {
        var m: [String: Any] = ["jsonrpc": Mcp.jsonRpcVersion, "method": method]
        if let p = params { m["params"] = p }
        send(m)
    }

    func request(_ method: String, params: [String: Any] = [:]) throws -> [String: Any] {
        let id = nextId
        nextId += 1
        send(["jsonrpc": Mcp.jsonRpcVersion, "id": id, "method": method, "params": params])
        guard let resp = responses.removeValue(forKey: id) else {
            throw McpError(code: Mcp.internalError, message: "no response for '\(method)' (id \(id))", data: nil)
        }
        if let e = resp["error"] as? [String: Any] {
            throw McpError(code: (e["code"] as? Int) ?? Mcp.internalError,
                           message: (e["message"] as? String) ?? "", data: e["data"])
        }
        return (resp["result"] as? [String: Any]) ?? [:]
    }

    private func send(_ m: [String: Any]) {
        if let data = try? JSONSerialization.data(withJSONObject: m),
           let text = String(data: data, encoding: .utf8) {
            transport.send(text)
        }
    }
}

/// The LLM-output -> TTS-input boundary as MCP (mirror of Android `McpTts`): one `speak` tool,
/// PCM returned by handle in `structuredContent.pcm_ref`.
final class McpTts {
    static let speakDescriptor = McpToolDescriptor(
        name: Mcp.ttsSpeakTool,
        description: "Synthesize one short spoken clause (<= one TTS chunk) and return a PCM handle.",
        inputSchema: [
            "type": "object",
            "properties": [
                "text": ["type": "string", "description": "speakable clause text"],
                "language": ["type": "string", "description": "ko | en"],
                "clause_index": ["type": "integer", "description": "clause position within the turn"],
                "chunk_id": ["type": "string", "description": "correlation id for the clause"],
                "flow_steps": ["type": "integer", "description": "flow-matching steps (quality/latency)"],
                "generation_id": ["type": "integer", "description": "cancel epoch of the turn"],
            ],
            "required": ["text"],
        ]
    )

    let server: McpServer
    let client: McpClient
    private var pcmByRef: [String: [Float]] = [:]
    private let synthesize: (String, String, Int) -> [Float]?

    init(synthesize: @escaping (_ text: String, _ language: String, _ flowSteps: Int) -> [Float]?) {
        self.synthesize = synthesize
        let (c, s) = InProcessTransport.pair()
        var store: ((String, [Float]) -> Void)!
        server = McpServer(
            name: Mcp.ttsServerName,
            version: "1",
            tools: [McpToolHandler(descriptor: McpTts.speakDescriptor) { args in
                guard let text = args["text"] as? String, !text.trimmingCharacters(in: .whitespaces).isEmpty else {
                    return McpCallResult(text: "missing 'text'", isError: true)
                }
                let language = (args["language"] as? String) ?? "ko"
                let k = (args["flow_steps"] as? Int) ?? 6
                let chunkId = (args["chunk_id"] as? String) ?? "c?"
                let t0 = Date()
                guard let pcm = synthesize(text, language, k) ?? synthesize(text, language, k) else {
                    return McpCallResult(text: "synthesis returned nil (after retry)", isError: true)
                }
                let ref = "pcm:\(chunkId):\(UInt64(Date().timeIntervalSince1970 * 1_000_000))"
                store(ref, pcm)
                return McpCallResult(
                    text: "synthesized \(pcm.count) samples",
                    structuredContent: [
                        "pcm_ref": ref,
                        "sample_rate": 44_100,
                        "num_samples": pcm.count,
                        "synth_ms": Int(Date().timeIntervalSince(t0) * 1000),
                        "chunk_id": chunkId,
                        "clause_index": (args["clause_index"] as? Int) ?? -1,
                        "language": language,
                    ]
                )
            }],
            transport: s
        )
        client = McpClient(transport: c)
        store = { [weak self] ref, pcm in self?.pcmByRef[ref] = pcm }
        _ = try? client.initialize()
    }

    /// Returns nil when synthesis failed; the clause is dropped by the caller.
    func speak(text: String, language: String, clauseIndex: Int, chunkId: String,
               flowSteps: Int, generationId: Int) -> [Float]? {
        let args: [String: Any] = [
            "text": text, "language": language, "clause_index": clauseIndex,
            "chunk_id": chunkId, "flow_steps": flowSteps, "generation_id": generationId,
        ]
        guard let r = try? client.callTool(Mcp.ttsSpeakTool, arguments: args), !r.isError,
              let ref = r.structuredContent?["pcm_ref"] as? String else { return nil }
        return pcmByRef.removeValue(forKey: ref)
    }
}
