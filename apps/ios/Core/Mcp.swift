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
    static let asrServerName = "voxedge-asr"
    static let asrTranscribeTool = "transcribe"

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

/// The captured-utterance -> ASR boundary as MCP (mirror of Android `McpAsr`): one `transcribe`
/// tool; audio arrives by handle (`pcm_ref`, in-process) or inline (`audio_wav`, base64 16-bit
/// PCM WAV, external callers); the transcript is the result's text block and
/// `structuredContent.text`.
final class McpAsr {
    static let transcribeDescriptor = McpToolDescriptor(
        name: Mcp.asrTranscribeTool,
        description: "Transcribe one endpointed mono utterance (audio by PCM handle in-process, or inline 16-bit WAV) and return the text.",
        inputSchema: [
            "type": "object",
            "properties": [
                "pcm_ref": ["type": "string", "description": "in-process PCM handle (float mono)"],
                "audio_wav": ["type": "string", "description": "base64 16-bit PCM WAV (external callers)"],
                "sample_rate": ["type": "integer", "description": "sample rate of the pcm_ref audio"],
                "language": ["type": "string", "description": "ko | en (switches the engine language)"],
                "engine": ["type": "string", "description": "owned | platform (default: the loop's current engine)"],
                "utterance_id": ["type": "string", "description": "correlation id for the utterance"],
                "generation_id": ["type": "integer", "description": "cancel epoch, when already assigned"],
            ],
        ]
    )

    struct Result {
        let text: String
        let asrMs: Int
        let engine: String
    }

    let server: McpServer
    let client: McpClient
    private var pcmByRef: [String: ([Float], Int)] = [:]
    private var utteranceSeq = 0

    /// `transcribe(samples, sampleRate, engine?, language?)` -> (text, engineName) or nil on failure.
    init(transcribe: @escaping (_ samples: [Float], _ sampleRate: Int, _ engine: String?, _ language: String?) -> (String, String)?) {
        let (c, s) = InProcessTransport.pair()
        var take: ((String) -> ([Float], Int)?)!
        server = McpServer(
            name: Mcp.asrServerName,
            version: "1",
            tools: [McpToolHandler(descriptor: McpAsr.transcribeDescriptor) { args in
                let utteranceId = (args["utterance_id"] as? String) ?? "u?"
                let audio: ([Float], Int)
                if let ref = args["pcm_ref"] as? String {
                    guard let a = take(ref) else { return McpCallResult(text: "pcm_ref '\(ref)' not found", isError: true) }
                    audio = a
                } else if let b64 = args["audio_wav"] as? String {
                    guard let bytes = Data(base64Encoded: b64) else {
                        return McpCallResult(text: "audio_wav is not valid base64", isError: true)
                    }
                    guard let a = McpAsr.wavSamples(bytes) else {
                        return McpCallResult(text: "audio_wav is not a 16-bit PCM WAV", isError: true)
                    }
                    audio = a
                } else {
                    return McpCallResult(text: "missing audio: pass 'pcm_ref' or 'audio_wav'", isError: true)
                }
                if audio.0.isEmpty { return McpCallResult(text: "audio is empty", isError: true) }
                let t0 = Date()
                guard let (text, engine) = transcribe(audio.0, audio.1, args["engine"] as? String, args["language"] as? String) else {
                    return McpCallResult(text: "transcription failed", isError: true)
                }
                return McpCallResult(
                    text: text,
                    structuredContent: [
                        "text": text,
                        "asr_ms": Int(Date().timeIntervalSince(t0) * 1000),
                        "engine": engine,
                        "sample_rate": audio.1,
                        "num_samples": audio.0.count,
                        "utterance_id": utteranceId,
                    ]
                )
            }],
            transport: s
        )
        client = McpClient(transport: c)
        take = { [weak self] ref in self?.pcmByRef.removeValue(forKey: ref) }
        _ = try? client.initialize()
    }

    /// Loop side: hand one endpointed utterance to ASR through MCP. Nil on a protocol or tool error.
    func transcribe(samples: [Float], sampleRate: Int, engine: String? = nil, language: String? = nil) -> Result? {
        utteranceSeq += 1
        let utteranceId = "u\(utteranceSeq)"
        let ref = "pcm:\(utteranceId):\(UInt64(Date().timeIntervalSince1970 * 1_000_000))"
        pcmByRef[ref] = (samples, sampleRate)
        var args: [String: Any] = ["pcm_ref": ref, "sample_rate": sampleRate, "utterance_id": utteranceId]
        if let e = engine { args["engine"] = e }
        if let l = language { args["language"] = l }
        defer { pcmByRef.removeValue(forKey: ref) }
        guard let r = try? client.callTool(Mcp.asrTranscribeTool, arguments: args), !r.isError,
              let sc = r.structuredContent else { return nil }
        return Result(
            text: (sc["text"] as? String) ?? r.text,
            asrMs: (sc["asr_ms"] as? Int) ?? 0,
            engine: (sc["engine"] as? String) ?? ""
        )
    }

    /// RIFF/WAVE 16-bit PCM (mono, or channels averaged) -> float samples + sample rate.
    static func wavSamples(_ data: Data) -> ([Float], Int)? {
        let b = [UInt8](data)
        func le16(_ at: Int) -> Int { Int(b[at]) | (Int(b[at + 1]) << 8) }
        func le32(_ at: Int) -> Int { le16(at) | (le16(at + 2) << 16) }
        func tag(_ at: Int) -> String { String(bytes: b[at..<at + 4], encoding: .ascii) ?? "" }
        guard b.count >= 12, tag(0) == "RIFF", tag(8) == "WAVE" else { return nil }
        var pos = 12
        var channels = 0, sampleRate = 0, bits = 0, format = 0
        while pos + 8 <= b.count {
            let id = tag(pos)
            let size = le32(pos + 4)
            let body = pos + 8
            guard size >= 0, body + size <= b.count else { return nil }
            if id == "fmt " {
                guard size >= 16 else { return nil }
                format = le16(body); channels = le16(body + 2); sampleRate = le32(body + 4); bits = le16(body + 14)
            } else if id == "data" {
                guard format == 1, bits == 16, channels >= 1, sampleRate > 0 else { return nil }
                let frames = size / (2 * channels)
                var out = [Float](repeating: 0, count: frames)
                var p = body
                for i in 0..<frames {
                    var acc: Float = 0
                    for _ in 0..<channels {
                        acc += Float(Int16(truncatingIfNeeded: le16(p))) / 32768
                        p += 2
                    }
                    out[i] = acc / Float(channels)
                }
                return (out, sampleRate)
            }
            pos = body + size + (size & 1)
        }
        return nil
    }
}
