import Foundation

final class PcmPlayer {
    func playMono(_ samples: [Float], sampleRate: Int = 44_100) async {}
    func interrupt() {}
}
