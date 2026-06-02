import Foundation

final class GenerationEpoch {
    private var current: Int64 = 0

    func next() -> Int64 {
        current += 1
        return current
    }

    func cancel() -> Int64 {
        current += 1
        return current
    }

    func isCurrent(_ generationId: Int64) -> Bool {
        current == generationId
    }
}
