package com.conversationalai.agent.core

import com.conversationalai.agent.core.PromptAssembler.Lang
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmSessionPolicyTest {

    private fun canContinue(
        capable: Boolean = true,
        warm: Boolean = true,
        occupancy: Int = 20,
        sessionLang: Lang? = Lang.KO,
        turnLang: Lang = Lang.KO,
    ) = LlmSessionPolicy.canContinue(capable, warm, occupancy, sessionLang, turnLang)

    @Test
    fun continuesWarmSameLanguageLowOccupancySession() {
        assertTrue(canContinue())
    }

    @Test
    fun reprefillsWhenEngineHasNoSessionSupport() {
        assertFalse(canContinue(capable = false))
    }

    @Test
    fun reprefillsColdSession() {
        // First turn, or after abort/overflow/error colded the session.
        assertFalse(canContinue(warm = false))
        assertFalse(canContinue(warm = false, sessionLang = null))
    }

    @Test
    fun reprefillsOnLanguageSwitch() {
        assertFalse(canContinue(sessionLang = Lang.KO, turnLang = Lang.EN))
        assertFalse(canContinue(sessionLang = Lang.EN, turnLang = Lang.KO))
    }

    @Test
    fun reprefillsAtOccupancyThreshold() {
        assertTrue(canContinue(occupancy = LlmSessionPolicy.MAX_OCCUPANCY_PERCENT - 1))
        assertFalse(canContinue(occupancy = LlmSessionPolicy.MAX_OCCUPANCY_PERCENT))
        assertFalse(canContinue(occupancy = 100))
    }

    @Test
    fun unknownOccupancyDoesNotForceReprefill() {
        // -1 = engine can't report occupancy; overflow is still caught via CONTEXT_EXCEEDED.
        assertTrue(canContinue(occupancy = -1))
    }
}
