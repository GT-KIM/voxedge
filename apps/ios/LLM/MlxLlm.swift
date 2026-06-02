import Foundation

final class MlxLlm: LlmEngine {
    func generate(prompt: String, onToken: @escaping (String) -> Void) async throws {}
    func abort() {}
}
