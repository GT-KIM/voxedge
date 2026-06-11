import Foundation

enum ConversationAction: Equatable {
    case updateTypedText(String)
    case changeLanguage(String)
    case submitTypedTurn
    case startHandsFree
    case stopHandsFree
    case startPushToTalk
    case stopPushToTalk
    case cancelCurrentTurn
    case toggleBargeIn
    case openDiagnostics
    case closeDiagnostics
    // Settings sheet (parity with Android's settings actions).
    case openSettings
    case closeSettings
    case selectLlmModel(String)
    case setSampling(Double, Int, Double)
    case setMaxResponseTokens(Int)
    case resetSampling
    case toggleTools
    case runDebugSpeak
    case runDebugAskLlm
    case runDebugConverse
    case runDebugAsrTest
}
