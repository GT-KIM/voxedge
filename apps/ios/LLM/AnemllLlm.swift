import Foundation

final class AnemllLlm: LlmEngine {
    func generate(prompt: String, onToken: @escaping (String) -> Void) async throws {}
    func abort() {}
}
