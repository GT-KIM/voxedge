package com.conversationalai.agent.devicetools

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure, Android-free validation and formatting for the intent-backed device tools. The tool
 * classes in [DeviceTools] stay thin — they call into here to parse/clamp the model's arguments,
 * then only build and launch the platform Intent. Splitting the rules out makes them unit-testable
 * on the JVM (no Context/Robolectric), which is where the real bugs live (coercion, clamping,
 * number filtering, the calendar time-window math).
 */
object DeviceToolLogic {

    /** A validated argument parse: [Ok] carries the normalized value, [Err] a model-readable reason. */
    sealed interface Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>
        data class Err(val message: String) : Parsed<Nothing>
    }

    private fun blank(s: String?): String? = s?.takeIf { it.isNotBlank() }
    private fun intArg(args: Map<String, String>, key: String): Int? =
        args[key]?.toDoubleOrNull()?.toInt()

    // --- set_timer ---

    data class TimerPlan(val seconds: Int, val minutes: Int, val label: String?)

    fun timer(args: Map<String, String>): Parsed<TimerPlan> {
        val minutes = intArg(args, "minutes") ?: return Parsed.Err("missing or invalid 'minutes'")
        if (minutes !in 1..MAX_MINUTES) return Parsed.Err("'minutes' must be 1..$MAX_MINUTES")
        return Parsed.Ok(TimerPlan(seconds = minutes * 60, minutes = minutes, label = blank(args["label"])))
    }

    // --- set_alarm ---

    data class AlarmPlan(val hour: Int, val minute: Int, val label: String?)

    fun alarm(args: Map<String, String>): Parsed<AlarmPlan> {
        val hour = intArg(args, "hour")
        val minute = intArg(args, "minute")
        if (hour == null || hour !in 0..23 || minute == null || minute !in 0..59) {
            return Parsed.Err("need 'hour' 0-23 and 'minute' 0-59")
        }
        return Parsed.Ok(AlarmPlan(hour, minute, blank(args["label"])))
    }

    // --- flashlight ---

    fun flashlightOn(args: Map<String, String>): Parsed<Boolean> = when (args["state"]?.lowercase()) {
        "on", "true", "1" -> Parsed.Ok(true)
        "off", "false", "0" -> Parsed.Ok(false)
        else -> Parsed.Err("'state' must be 'on' or 'off'")
    }

    // --- battery_status (formatting only; the readings come from the platform) ---

    fun batteryPercent(level: Int, scale: Int): Int =
        if (level >= 0 && scale > 0) level * 100 / scale else -1

    fun batterySummary(pct: Int, charging: Boolean, airplane: Boolean): String =
        "battery $pct%, " + (if (charging) "charging" else "not charging") +
            ", airplane mode " + (if (airplane) "on" else "off")

    // --- create_calendar_event ---

    data class CalendarPlan(
        val title: String,
        val location: String?,
        val startMs: Long?,
        val endMs: Long?,
        val whenText: String,
    )

    /** [today]/[zone] are injected so the epoch math is deterministic (and testable). A missing or
     *  out-of-range hour yields an all-day-ish event with no specific time. */
    fun calendar(args: Map<String, String>, today: LocalDate, zone: ZoneId): Parsed<CalendarPlan> {
        val title = blank(args["title"]) ?: return Parsed.Err("missing 'title'")
        val location = blank(args["location"])
        val hour = intArg(args, "hour")
        val minute = intArg(args, "minute") ?: 0
        if (hour != null && hour in 0..23 && minute in 0..59) {
            val start = LocalDateTime.of(today, LocalTime.of(hour, minute))
            val startMs = start.atZone(zone).toInstant().toEpochMilli()
            val durationMin = intArg(args, "duration_minutes")?.coerceIn(1, MAX_MINUTES) ?: 60
            return Parsed.Ok(
                CalendarPlan(
                    title = title,
                    location = location,
                    startMs = startMs,
                    endMs = startMs + durationMin * 60_000L,
                    whenText = "today at %02d:%02d for %d min".format(hour, minute, durationMin),
                ),
            )
        }
        return Parsed.Ok(CalendarPlan(title, location, startMs = null, endMs = null, whenText = "no specific time"))
    }

    // --- dial_number / send_sms (phone-number normalization) ---

    private fun phoneNumber(raw: String?): String? =
        raw?.filter { it.isDigit() || it in "+*#" }?.takeIf { it.isNotBlank() }

    fun dialNumber(args: Map<String, String>): Parsed<String> =
        phoneNumber(args["number"])?.let { Parsed.Ok(it) }
            ?: Parsed.Err("missing or invalid 'number'")

    data class SmsPlan(val number: String, val message: String)

    fun sms(args: Map<String, String>): Parsed<SmsPlan> {
        val number = phoneNumber(args["number"]) ?: return Parsed.Err("missing or invalid 'number'")
        return Parsed.Ok(SmsPlan(number, args["message"].orEmpty()))
    }

    // --- navigate ---

    fun navDestination(args: Map<String, String>): Parsed<String> =
        blank(args["destination"])?.let { Parsed.Ok(it) } ?: Parsed.Err("missing 'destination'")

    private const val MAX_MINUTES = 24 * 60
}
