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
    case runDebugSpeak
    case runDebugAskLlm
    case runDebugConverse
    case runDebugAsrTest
}
