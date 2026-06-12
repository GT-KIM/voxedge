package com.conversationalai.agent.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Written->spoken normalization for TTS: clock times, percents, decimals, integers, KO counters. */
class SpokenTextNormalizerTest {

    private fun ko(s: String) = SpokenTextNormalizer.normalize(s, "ko")
    private fun en(s: String) = SpokenTextNormalizer.normalize(s, "en")

    // --- Korean number readings (Sino) ---

    @Test
    fun koNumberReadsSinoKorean() {
        assertEquals("영", SpokenTextNormalizer.koNumber(0))                       // 영
        assertEquals("십", SpokenTextNormalizer.koNumber(10))                      // 십
        assertEquals("십오", SpokenTextNormalizer.koNumber(15))                // 십오
        assertEquals("이십사", SpokenTextNormalizer.koNumber(24))          // 이십사
        assertEquals("백", SpokenTextNormalizer.koNumber(100))                     // 백
        assertEquals("천", SpokenTextNormalizer.koNumber(1000))                    // 천
        assertEquals("만", SpokenTextNormalizer.koNumber(10000))                   // 만
        // 2026 -> 이천이십육
        assertEquals("이천이십육", SpokenTextNormalizer.koNumber(2026))
        // 36500 -> 삼만 육천오백 (no space in reading)
        assertEquals(
            "삼만육천오백",
            SpokenTextNormalizer.koNumber(36500),
        )
    }

    @Test
    fun koClockUsesNativeHoursAndSinoMinutes() {
        // 3:30 -> 세 시 삼십 분
        assertEquals("세 시 삼십 분", ko("3:30"))
        // 12:00 -> 열두 시
        assertEquals("열두 시", ko("12:00"))
        // 15:00 -> 오후 세 시
        assertEquals("오후 세 시", ko("15:00"))
    }

    @Test
    fun koHourCounterReadsNative() {
        // "3시에 보자" -> "세 시에 보자"
        assertEquals("세 시에 보자", ko("3시에 보자"))
        // 시간 (duration) is NOT an hour-of-day: "3시간" -> "삼시간"
        assertEquals("삼시간", ko("3시간"))
    }

    @Test
    fun koMeridiemTokensAreTranslated() {
        // "3:05 PM" -> "세 시 오 분 오후"... PM precedes in tool output as suffix; we just swap token
        assertEquals("오후 세 시 오 분", ko("PM 3:05"))
    }

    @Test
    fun koPercentDecimalsAndGroupedNumbers() {
        // 25% -> 이십오 퍼센트
        assertEquals("이십오 퍼센트", ko("25%"))
        // 0.008 -> 영점영영팔
        assertEquals("영점영영팔", ko("0.008"))
        // 1,000 -> 천
        assertEquals("천", ko("1,000"))
    }

    @Test
    fun koPlainTextPassesThrough() {
        val s = "안녕하세요, 반갑습니다."
        assertEquals(s, ko(s))
    }

    // --- English ---

    @Test
    fun enNumberSpellsIntegers() {
        assertEquals("zero", SpokenTextNormalizer.enNumber(0))
        assertEquals("fifteen", SpokenTextNormalizer.enNumber(15))
        assertEquals("forty two", SpokenTextNormalizer.enNumber(42))
        assertEquals("one hundred five", SpokenTextNormalizer.enNumber(105))
        assertEquals("two thousand twenty six", SpokenTextNormalizer.enNumber(2026))
        assertEquals("three million", SpokenTextNormalizer.enNumber(3_000_000))
    }

    @Test
    fun enClockReadsNaturally() {
        assertEquals("It is three thirty.", en("It is 3:30."))
        assertEquals("twelve o'clock", en("12:00"))
        assertEquals("three oh five PM", en("15:05"))
        assertEquals("twelve oh five AM", en("0:05"))
    }

    @Test
    fun enPercentDecimalsAndGroupedNumbers() {
        assertEquals("twenty five percent", en("25%"))
        assertEquals("zero point zero zero eight", en("0.008"))
        assertEquals("one thousand won", en("1,000 won"))
    }

    @Test
    fun enPlainTextPassesThrough() {
        assertEquals("Nothing numeric here.", en("Nothing numeric here."))
    }

    @Test
    fun enLongDigitStringsAreSpelledDigitByDigit() {
        assertEquals(
            "one two three four five six seven eight",
            en("12345678"),
        )
    }
}
