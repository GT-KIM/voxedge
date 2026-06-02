import Foundation

protocol ConversationBackend {
    func submitTypedTurn(_ text: String) async throws
    func startHandsFree() async throws
    func stopHandsFree() async
    func startPushToTalk() async throws
    func stopPushToTalk() async throws
    func cancelCurrentTurn() async
    func changeLanguage(_ language: String) async throws
    func runDebugSpeak(_ text: String) async throws
    func runDebugAskLlm(_ text: String) async throws
    func runDebugConverse(_ text: String) async throws
    func runDebugAsrTest() async throws
}

struct StubConversationBackend: ConversationBackend {
    func submitTypedTurn(_ text: String) async throws {}
    func startHandsFree() async throws {}
    func stopHandsFree() async {}
    func startPushToTalk() async throws {}
    func stopPushToTalk() async throws {}
    func cancelCurrentTurn() async {}
    func changeLanguage(_ language: String) async throws {}
    func runDebugSpeak(_ text: String) async throws {}
    func runDebugAskLlm(_ text: String) async throws {}
    func runDebugConverse(_ text: String) async throws {}
    func runDebugAsrTest() async throws {}
}
