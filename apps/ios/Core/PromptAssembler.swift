import Foundation

enum PromptAssembler {
    static func chatML(_ userText: String, history: [(user: String, assistant: String)] = []) -> String {
        var parts = ["<|im_start|>system\nYou are an on-device spoken assistant.<|im_end|>"]
        for turn in history {
            parts.append("<|im_start|>user\n\(turn.user)<|im_end|>")
            parts.append("<|im_start|>assistant\n\(turn.assistant)<|im_end|>")
        }
        parts.append("<|im_start|>user\n\(userText)<|im_end|>")
        parts.append("<|im_start|>assistant\n")
        return parts.joined(separator: "\n")
    }
}
