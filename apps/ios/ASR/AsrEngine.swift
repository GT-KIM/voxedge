import Foundation

protocol AsrEngine {
    var language: String { get }
    func setLanguage(_ language: String) async throws
    func transcribe(samples: [Float], sampleRate: Int) async throws -> String
}
