package com.conversationalai.agent.core

/**
 * Converts written forms the TTS reads awkwardly into spoken words, per output language —
 * applied per clause between the segmenter and the TTS input builder. Covers the patterns the
 * agent actually produces (tool results + model text): clock times, AM/PM, percentages,
 * decimals, comma-grouped numbers, Korean counters (N시/N분/N초/N분 동안), bare integers.
 *
 * Korean numerals use Sino-Korean (일이삼…만) except clock HOURS, which use native Korean
 * (한/두/세… 열두 시). English integers are spelled to the en-US convention.
 *
 * Pure Kotlin, ASCII-only source (Hangul via escapes) — JVM-tested in SpokenTextNormalizerTest.
 */
object SpokenTextNormalizer {

    fun normalize(text: String, lang: String): String =
        if (lang == "ko") normalizeKo(text) else normalizeEn(text)

    // ---------------------------------------------------------------- Korean

    private val KO_DIGITS = arrayOf(
        "영", "일", "이", "삼", "사",
        "오", "육", "칠", "팔", "구",
    ) // 영일이삼사오육칠팔구
    private val KO_SMALL_UNITS = arrayOf("", "십", "백", "천")          // 십백천
    private val KO_BIG_UNITS = arrayOf("", "만", "억")                      // 만억
    private val KO_NATIVE_HOURS = arrayOf(
        "", "한", "두", "세", "네", "다섯", "여섯",
        "일곱", "여덟", "아홉", "열", "열한", "열두",
    ) // 한두세네다섯여섯일곱여덟아홉열열한열두

    /** Sino-Korean reading of a non-negative integer (supports up to 억 range). */
    fun koNumber(n: Long): String {
        if (n == 0L) return KO_DIGITS[0]
        val groups = ArrayList<Int>()   // base-10000 groups, least significant first
        var v = n
        while (v > 0) { groups.add((v % 10000).toInt()); v /= 10000 }
        val sb = StringBuilder()
        for (g in groups.indices.reversed()) {
            val part = groups[g]
            if (part == 0) continue
            sb.append(koFourDigits(part, dropLeadingOne = g > 0))
            sb.append(KO_BIG_UNITS[g])
        }
        return sb.toString()
    }

    private fun koFourDigits(n: Int, dropLeadingOne: Boolean): String {
        val sb = StringBuilder()
        var v = n
        for (p in 3 downTo 0) {
            val d = v / POW10[p]
            v %= POW10[p]
            if (d == 0) continue
            // "일천이백" -> "천이백" (and "일만" -> "만" via dropLeadingOne at group level)
            val sayDigit = !(d == 1 && (p > 0 || (dropLeadingOne && n == 1)))
            if (sayDigit) sb.append(KO_DIGITS[d])
            sb.append(KO_SMALL_UNITS.getOrElse(p) { "" })
        }
        return sb.toString()
    }

    private fun koDigitsVerbatim(s: String): String =
        s.map { KO_DIGITS[it - '0'] }.joinToString("")

    /** H:MM as Korean speech: native hour + sino minutes, 오전/오후 for 24h or explicit AM/PM. */
    private fun koClock(h: Int, min: Int, pm: Boolean?): String {
        val meridiem = when {
            pm == true || (pm == null && h > 12) -> "오후 "   // 오후
            pm == false || h == 0 -> "오전 "                       // 오전
            else -> ""
        }
        val h12 = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
        val hours = meridiem + KO_NATIVE_HOURS[h12] + " 시"            // N 시
        return if (min == 0) hours
        else hours + " " + koNumber(min.toLong()) + " 분"              // N 분
    }

    private fun normalizeKo(text: String): String {
        var t = text
        // Times with an attached meridiem ("3:05 PM", "3:05pm") — meridiem becomes a prefix.
        // Lowercase "am"/"pm" only count when glued to a time; bare uppercase tokens (tool
        // output like "PM 3:05") are translated separately, and the English word "am" is safe.
        t = Regex("(\\d{1,2}):(\\d{2})\\s*([AaPp])\\.?[Mm]\\.?(?![A-Za-z])").replace(t) { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) m.value
            else koClock(h, min, pm = m.groupValues[3].equals("p", ignoreCase = true))
        }
        t = t.replace(Regex("\\bA\\.?M\\.?\\b"), "오전")    // 오전
        t = t.replace(Regex("\\bP\\.?M\\.?\\b"), "오후")    // 오후
        // Bare clock H:MM.
        t = Regex("(\\d{1,2}):(\\d{2})(?!\\d)").replace(t) { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) m.value else koClock(h, min, pm = null)
        }
        // Hour counter N시 reads native (시간/durations and other counters stay sino via the
        // generic integer pass below).
        t = Regex("(\\d{1,2})\\s*시(?!간)").replace(t) { m ->
            val h = m.groupValues[1].toInt()
            when {
                h in 1..12 -> KO_NATIVE_HOURS[h] + " 시"
                h in 13..23 -> "오후 " + KO_NATIVE_HOURS[h - 12] + " 시"   // 오후
                else -> m.value
            }
        }
        // Strip digit-group commas, then decimals, percents, remaining integers.
        t = Regex("(\\d),(?=\\d{3})").replace(t) { it.groupValues[1] }
        t = Regex("(\\d+)\\.(\\d+)").replace(t) { m ->
            koNumber(m.groupValues[1].toLong()) + "점" + koDigitsVerbatim(m.groupValues[2]) // 점
        }
        t = Regex("(\\d+)\\s*%").replace(t) { m ->
            koNumber(m.groupValues[1].toLong()) + " 퍼센트"    // 퍼센트
        }
        t = Regex("\\d+").replace(t) { m ->
            val s = m.value
            if (s.length <= 9) koNumber(s.toLong()) else koDigitsVerbatim(s)
        }
        return t
    }

    // --------------------------------------------------------------- English

    private val EN_ONES = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
        "eighteen", "nineteen",
    )
    private val EN_TENS = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    )

    /** English reading of a non-negative integer (up to the millions). */
    fun enNumber(n: Long): String = when {
        n < 20 -> EN_ONES[n.toInt()]
        n < 100 -> EN_TENS[(n / 10).toInt()] +
            (if (n % 10 != 0L) " " + EN_ONES[(n % 10).toInt()] else "")
        n < 1000 -> EN_ONES[(n / 100).toInt()] + " hundred" +
            (if (n % 100 != 0L) " " + enNumber(n % 100) else "")
        n < 1_000_000 -> enNumber(n / 1000) + " thousand" +
            (if (n % 1000 != 0L) " " + enNumber(n % 1000) else "")
        else -> enNumber(n / 1_000_000) + " million" +
            (if (n % 1_000_000 != 0L) " " + enNumber(n % 1_000_000) else "")
    }

    /** H:MM as English speech: "three thirty", "twelve o'clock", "three oh five PM". */
    private fun enClock(h: Int, min: Int, pm: Boolean?): String {
        val suffix = when {
            pm == true || (pm == null && h > 12) -> " PM"
            pm == false || h == 0 -> " AM"
            else -> ""
        }
        val h12 = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
        return when {
            min == 0 -> enNumber(h12.toLong()) + " o'clock" + suffix
            min < 10 -> enNumber(h12.toLong()) + " oh " + enNumber(min.toLong()) + suffix
            else -> enNumber(h12.toLong()) + " " + enNumber(min.toLong()) + suffix
        }
    }

    private fun normalizeEn(text: String): String {
        var t = text
        // Times with an attached meridiem first ("3:05pm" must not become "three oh fivepm"),
        // then bare H:MM. Standalone "am"/"pm" words are left alone ("I am" is not a time).
        t = Regex("(\\d{1,2}):(\\d{2})\\s*([AaPp])\\.?[Mm]\\.?(?![A-Za-z])").replace(t) { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) m.value
            else enClock(h, min, pm = m.groupValues[3].equals("p", ignoreCase = true))
        }
        t = Regex("(\\d{1,2}):(\\d{2})(?!\\d)").replace(t) { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) m.value else enClock(h, min, pm = null)
        }
        t = Regex("(\\d),(?=\\d{3})").replace(t) { it.groupValues[1] }
        t = Regex("(\\d+)\\.(\\d+)").replace(t) { m ->
            enNumber(m.groupValues[1].toLong()) + " point " +
                m.groupValues[2].map { EN_ONES[it - '0'] }.joinToString(" ")
        }
        t = Regex("(\\d+)\\s*%").replace(t) { m -> enNumber(m.groupValues[1].toLong()) + " percent" }
        t = Regex("\\d+").replace(t) { m ->
            val s = m.value
            if (s.length <= 7) enNumber(s.toLong())
            else s.map { EN_ONES[it - '0'] }.joinToString(" ")
        }
        return t
    }

    private val POW10 = intArrayOf(1, 10, 100, 1000)
}
