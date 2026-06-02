import Foundation

protocol LlmEngine {
    func generate(prompt: String, onToken: @escaping (String) -> Void) async throws
    func abort()
}
