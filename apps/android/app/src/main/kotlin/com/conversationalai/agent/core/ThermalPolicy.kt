package com.conversationalai.agent.core

/**
 * Thermal degrade policy for the speech loop (docs/design/speech_loop_state_machine.md, "Power /
 * thermal policy"; events `runtime.thermal` / `runtime.degrade` in shared/mcp).
 *
 * Input is the OS thermal status (Android PowerManager.THERMAL_STATUS_*: 0 NONE, 1 LIGHT,
 * 2 MODERATE, 3 SEVERE, 4 CRITICAL, 5 EMERGENCY, 6 SHUTDOWN), the primary signal per the config
 * contract (`runtime.use_os_thermal_state`). Measured on the SM8750 (2026-10-06): the HTP LLM path
 * reaches status 1 after ~9 min of continuous turns from a cool start and status 2 within 4 min
 * from a warm one, with decode slowing ~18 % at status 2; the GPU path stayed at 0.
 *
 * Levels and actions:
 *  - NOMINAL  (status <= 1): no change. LIGHT is the first OS hint and came with no slowdown.
 *  - ELEVATED (status == 2): `reduce_flow_steps` (TTS K capped at the measured contention floor)
 *    and `shorten_response` (response-token cap halved) - less HTP work per turn.
 *  - CRITICAL (status >= 3): the above plus `pause_new_turns`; the loop keeps listening but answers
 *    with a notice until the device cools.
 * Hysteresis: a level steps DOWN only one step at a time and only when the status has fallen below
 * the level's entry point (ELEVATED clears at status 0, CRITICAL steps to ELEVATED at status <= 1),
 * so a sensor hovering at a threshold does not flap the TTS quality.
 *
 * `lower_power_profile` from the schema is not available at runtime on this stack (the HTP power
 * profile is fixed at graph-prepare time), so it is not emitted.
 */
class ThermalPolicy {

    enum class Level(val wire: String) { NOMINAL("nominal"), ELEVATED("elevated"), CRITICAL("critical") }

    /** Last level [update] settled on. */
    @Volatile var level: Level = Level.NOMINAL
        private set

    /** Last raw OS status seen. */
    @Volatile var status: Int = 0
        private set

    /** Feed a new OS status. Returns the new level when it changed, null otherwise. */
    @Synchronized
    fun update(osStatus: Int): Level? {
        status = osStatus
        val raw = rawLevel(osStatus)
        var next = level
        if (raw.ordinal > level.ordinal) {
            next = raw                                               // heat up: follow immediately
        } else {
            // Cool down: step through the levels while each one's clearing threshold is met. The
            // status callback fires on changes only, so one update may need to clear two levels.
            while (raw.ordinal < next.ordinal && osStatus <= downThreshold(next)) {
                next = Level.values()[next.ordinal - 1]
            }
        }
        if (next == level) return null
        level = next
        return next
    }

    /** Degrade actions in force for the current level (schema enum names). */
    val actions: List<String>
        get() = when (level) {
            Level.NOMINAL -> emptyList()
            Level.ELEVATED -> listOf("reduce_flow_steps", "shorten_response")
            Level.CRITICAL -> listOf("reduce_flow_steps", "shorten_response", "pause_new_turns")
        }

    /** New turns are refused while this is true (hands-free, speculative, and typed alike). */
    val pauseNewTurns: Boolean get() = level == Level.CRITICAL

    /** TTS flow steps to use given the user's setting. */
    fun flowSteps(configured: Int): Int =
        if (level == Level.NOMINAL) configured else minOf(configured, DEGRADED_FLOW_STEPS)

    /** Response-token cap to use given the user's setting. */
    fun maxResponseTokens(configured: Int): Int = when (level) {
        Level.NOMINAL -> configured
        Level.ELEVATED -> minOf(configured, maxOf(MIN_RESPONSE_TOKENS, configured / 2))
        Level.CRITICAL -> minOf(configured, MIN_RESPONSE_TOKENS)
    }

    companion object {
        /** Android PowerManager status values (duplicated so this class stays JVM-pure). */
        const val STATUS_NONE = 0
        const val STATUS_LIGHT = 1
        const val STATUS_MODERATE = 2
        const val STATUS_SEVERE = 3
        const val STATUS_CRITICAL = 4

        /** K floor under degrade: K=5 is the lowest the HTP tolerates next to the LLM (K=4 made
         *  SNPE executes fail under contention, 2026-06-12). */
        const val DEGRADED_FLOW_STEPS = 5
        /** Shortest useful spoken answer (~2 sentences at the measured decode rates). */
        const val MIN_RESPONSE_TOKENS = 40

        fun rawLevel(osStatus: Int): Level = when {
            osStatus >= STATUS_SEVERE -> Level.CRITICAL
            osStatus == STATUS_MODERATE -> Level.ELEVATED
            else -> Level.NOMINAL
        }

        private fun downThreshold(current: Level): Int = when (current) {
            Level.CRITICAL -> STATUS_LIGHT
            Level.ELEVATED -> STATUS_NONE
            Level.NOMINAL -> -1
        }

        fun statusName(osStatus: Int): String = when (osStatus) {
            0 -> "NONE"
            1 -> "LIGHT"
            2 -> "MODERATE"
            3 -> "SEVERE"
            4 -> "CRITICAL"
            5 -> "EMERGENCY"
            6 -> "SHUTDOWN"
            else -> "UNKNOWN($osStatus)"
        }
    }
}
