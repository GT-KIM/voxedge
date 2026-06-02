import Foundation

final class AudioCapture {
    private(set) var isRecording = false

    func start() -> Bool {
        isRecording = true
        return true
    }

    func stop() -> [Float] {
        isRecording = false
        return []
    }
}
