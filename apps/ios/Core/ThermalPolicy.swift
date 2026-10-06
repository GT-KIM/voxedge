import Foundation

/// Mirror of the Android `core/ThermalPolicy`: the same levels, actions, hysteresis, and caps, fed
/// by `ProcessInfo.thermalState` (nominal / fair / serious / critical) instead of the Android
/// PowerManager status. Build-ready; not yet compiled or run (no Mac build path).
final class ThermalPolicy {
    enum Level: Int, Comparable {
        case nominal = 0, elevated, critical
        var wire: String {
            switch self {
            case .nominal: return "nominal"
            case .elevated: return "elevated"
            case .critical: return "critical"
            }
        }
        static func < (a: Level, b: Level) -> Bool { a.rawValue < b.rawValue }
    }

    static let degradedFlowSteps = 5
    static let minResponseTokens = 40

    private(set) var level: Level = .nominal

    /// iOS thermal state -> Android-equivalent status (0 none, 1 light, 2 moderate, 3 severe, 4 critical).
    static func status(for state: ProcessInfo.ThermalState) -> Int {
        switch state {
        case .nominal: return 0
        case .fair: return 1
        case .serious: return 2
        case .critical: return 4
        @unknown default: return 2
        }
    }

    static func rawLevel(_ status: Int) -> Level {
        if status >= 3 { return .critical }
        if status == 2 { return .elevated }
        return .nominal
    }

    /// Returns the new level when it changed.
    func update(status: Int) -> Level? {
        let raw = ThermalPolicy.rawLevel(status)
        var next = level
        if raw > level {
            next = raw
        } else if raw < level, status <= downThreshold(level) {
            next = Level(rawValue: level.rawValue - 1) ?? .nominal
        }
        guard next != level else { return nil }
        level = next
        return next
    }

    var actions: [String] {
        switch level {
        case .nominal: return []
        case .elevated: return ["reduce_flow_steps", "shorten_response"]
        case .critical: return ["reduce_flow_steps", "shorten_response", "pause_new_turns"]
        }
    }

    var pauseNewTurns: Bool { level == .critical }

    func flowSteps(configured: Int) -> Int {
        level == .nominal ? configured : min(configured, ThermalPolicy.degradedFlowSteps)
    }

    func maxResponseTokens(configured: Int) -> Int {
        switch level {
        case .nominal: return configured
        case .elevated: return min(configured, max(ThermalPolicy.minResponseTokens, configured / 2))
        case .critical: return min(configured, ThermalPolicy.minResponseTokens)
        }
    }

    private func downThreshold(_ current: Level) -> Int {
        switch current {
        case .critical: return 1
        case .elevated: return 0
        case .nominal: return -1
        }
    }
}
