import SwiftUI

@main
struct ConversationApp: App {
    @State private var store = ConversationStore()

    var body: some Scene {
        WindowGroup {
            ConversationScreen(state: store.state, send: store.send)
        }
    }
}
