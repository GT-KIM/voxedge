import Foundation

enum McpEventType: String, CaseIterable, Equatable {
    case sessionStart = "session.start"
    case sessionEnd = "session.end"
    case asrPartial = "asr.partial"
    case asrFinal = "asr.final"
    case promptAssembled = "prompt.assembled"
    case llmTextDelta = "llm.text_delta"
    case llmTurnEnd = "llm.turn_end"
    case ttsChunkRequest = "tts.chunk_request"
    case ttsAudioChunk = "tts.audio_chunk"
    case ttsPlaybackStart = "tts.playback_start"
    case ttsPlaybackEnd = "tts.playback_end"
    case controlBargeIn = "control.barge_in"
    case controlCancel = "control.cancel"
    case runtimeThermal = "runtime.thermal"
    case runtimeDegrade = "runtime.degrade"
    case error = "error"
}
