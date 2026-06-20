package com.conversationalai.agent.devicetools

import com.conversationalai.agent.devicetools.DeviceToolLogic.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Pure validation/formatting rules for the intent-backed device tools (no Context needed). */
class DeviceToolLogicTest {

    private fun <T> ok(p: Parsed<T>): T = (p as Parsed.Ok<T>).value
    private fun err(p: Parsed<*>): String = (p as Parsed.Err).message

    // --- set_timer ---

    @Test
    fun timerCoercesAndCarriesLabel() {
        val plan = ok(DeviceToolLogic.timer(mapOf("minutes" to "5.0", "label" to "tea")))
        assertEquals(5, plan.minutes)
        assertEquals(300, plan.seconds)
        assertEquals("tea", plan.label)
    }

    @Test
    fun timerBlankLabelBecomesNull() {
        assertNull(ok(DeviceToolLogic.timer(mapOf("minutes" to "3", "label" to "   "))).label)
        assertNull(ok(DeviceToolLogic.timer(mapOf("minutes" to "3"))).label)
    }

    @Test
    fun timerRejectsMissingAndOutOfRange() {
        assertTrue(err(DeviceToolLogic.timer(emptyMap())).contains("minutes"))
        assertTrue(err(DeviceToolLogic.timer(mapOf("minutes" to "abc"))).contains("minutes"))
        assertTrue(err(DeviceToolLogic.timer(mapOf("minutes" to "0"))).contains("1..1440"))
        assertTrue(err(DeviceToolLogic.timer(mapOf("minutes" to "1441"))).contains("1..1440"))
        assertEquals(1440, ok(DeviceToolLogic.timer(mapOf("minutes" to "1440"))).minutes)  // boundary ok
    }

    // --- set_alarm ---

    @Test
    fun alarmAcceptsValidClockTimes() {
        val plan = ok(DeviceToolLogic.alarm(mapOf("hour" to "7", "minute" to "30", "label" to "wake")))
        assertEquals(7, plan.hour)
        assertEquals(30, plan.minute)
        assertEquals("wake", plan.label)
    }

    @Test
    fun alarmRejectsOutOfRange() {
        assertTrue(err(DeviceToolLogic.alarm(mapOf("hour" to "24", "minute" to "0"))).contains("hour"))
        assertTrue(err(DeviceToolLogic.alarm(mapOf("hour" to "8", "minute" to "60"))).contains("minute"))
        assertTrue(err(DeviceToolLogic.alarm(mapOf("hour" to "8"))).contains("minute"))   // missing minute
    }

    // --- flashlight ---

    @Test
    fun flashlightParsesSynonyms() {
        assertTrue(ok(DeviceToolLogic.flashlightOn(mapOf("state" to "ON"))))
        assertTrue(ok(DeviceToolLogic.flashlightOn(mapOf("state" to "1"))))
        assertTrue(!ok(DeviceToolLogic.flashlightOn(mapOf("state" to "off"))))
        assertTrue(!ok(DeviceToolLogic.flashlightOn(mapOf("state" to "false"))))
        assertTrue(err(DeviceToolLogic.flashlightOn(mapOf("state" to "maybe"))).contains("on"))
    }

    // --- battery_status ---

    @Test
    fun batteryPercentAndSummary() {
        assertEquals(50, DeviceToolLogic.batteryPercent(level = 50, scale = 100))
        assertEquals(50, DeviceToolLogic.batteryPercent(level = 1, scale = 2))
        assertEquals(-1, DeviceToolLogic.batteryPercent(level = -1, scale = 100))  // unavailable
        assertEquals(-1, DeviceToolLogic.batteryPercent(level = 5, scale = 0))     // bad scale
        assertEquals(
            "battery 80%, charging, airplane mode on",
            DeviceToolLogic.batterySummary(80, charging = true, airplane = true),
        )
        assertEquals(
            "battery 42%, not charging, airplane mode off",
            DeviceToolLogic.batterySummary(42, charging = false, airplane = false),
        )
    }

    // --- create_calendar_event ---

    private val today = LocalDate.of(2026, 6, 20)
    private val utc = ZoneOffset.UTC

    @Test
    fun calendarComputesTheTimeWindow() {
        val plan = ok(DeviceToolLogic.calendar(mapOf("title" to "Standup", "hour" to "9", "minute" to "30"), today, utc))
        assertEquals("Standup", plan.title)
        // 2026-06-20 09:30 UTC in epoch millis.
        val expectedStart = LocalDate.of(2026, 6, 20).atTime(9, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expectedStart, plan.startMs)
        assertEquals(expectedStart + 60 * 60_000L, plan.endMs)   // default 60 min
        assertTrue(plan.whenText.contains("09:30"))
        assertTrue(plan.whenText.contains("60 min"))
    }

    @Test
    fun calendarClampsDurationAndKeepsLocation() {
        val plan = ok(
            DeviceToolLogic.calendar(
                mapOf("title" to "Trip", "hour" to "8", "minute" to "0", "duration_minutes" to "5000", "location" to "Seoul"),
                today, utc,
            ),
        )
        assertEquals("Seoul", plan.location)
        val start = plan.startMs!!
        assertEquals(start + 1440 * 60_000L, plan.endMs)   // clamped to 24h
    }

    @Test
    fun calendarWithoutAValidTimeHasNoWindow() {
        val plan = ok(DeviceToolLogic.calendar(mapOf("title" to "Someday", "hour" to "25"), today, utc))
        assertNull(plan.startMs)
        assertNull(plan.endMs)
        assertEquals("no specific time", plan.whenText)
    }

    @Test
    fun calendarRequiresTitle() {
        assertTrue(err(DeviceToolLogic.calendar(mapOf("hour" to "9"), today, utc)).contains("title"))
        assertTrue(err(DeviceToolLogic.calendar(mapOf("title" to "  "), today, utc)).contains("title"))
    }

    // --- dial_number / send_sms ---

    @Test
    fun dialFiltersToDiallableCharacters() {
        assertEquals("+1800123#", ok(DeviceToolLogic.dialNumber(mapOf("number" to " +1 (800) 123 #"))))
        assertTrue(err(DeviceToolLogic.dialNumber(mapOf("number" to "call mom"))).contains("number"))
        assertTrue(err(DeviceToolLogic.dialNumber(emptyMap())).contains("number"))
    }

    @Test
    fun smsKeepsBodyAndCleansNumber() {
        val plan = ok(DeviceToolLogic.sms(mapOf("number" to "010-1234-5678", "message" to "hi there")))
        assertEquals("01012345678", plan.number)
        assertEquals("hi there", plan.message)
        assertEquals("", ok(DeviceToolLogic.sms(mapOf("number" to "5551234"))).message)   // body optional
        assertTrue(err(DeviceToolLogic.sms(mapOf("message" to "no number"))).contains("number"))
    }

    // --- navigate ---

    @Test
    fun navigateRequiresADestination() {
        assertEquals("Gangnam Station", ok(DeviceToolLogic.navDestination(mapOf("destination" to "Gangnam Station"))))
        assertTrue(err(DeviceToolLogic.navDestination(mapOf("destination" to " "))).contains("destination"))
        assertTrue(err(DeviceToolLogic.navDestination(emptyMap())).contains("destination"))
    }
}
