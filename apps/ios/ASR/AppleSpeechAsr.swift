import Foundation

struct AppleSpeechAsr: AsrEngine {
    let language: String

    func setLanguage(_ language: String) async throws {
        // Real implementation must gate on supportsOnDeviceRecognition and requiresOnDeviceRecognition.
    }

    func transcribe(samples: [Float], sampleRate: Int) async throws -> String {
        ""
    }
}
