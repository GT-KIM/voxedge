import Foundation

struct TtsInputs {
    let text: String
    let language: String
}

protocol TtsEngine {
    func synthesizeClause(_ inputs: TtsInputs, flowSteps: Int) async throws -> [Float]
}
