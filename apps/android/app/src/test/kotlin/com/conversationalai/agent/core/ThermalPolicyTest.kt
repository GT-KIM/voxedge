package com.conversationalai.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalPolicyTest {

    @Test
    fun lightStatusIsStillNominalModerateIsElevatedSevereIsCritical() {
        val p = ThermalPolicy()
        assertNull(p.update(ThermalPolicy.STATUS_LIGHT))
        assertEquals(ThermalPolicy.Level.NOMINAL, p.level)
        assertEquals(emptyList<String>(), p.actions)
        assertEquals(ThermalPolicy.Level.ELEVATED, p.update(ThermalPolicy.STATUS_MODERATE))
        assertEquals(listOf("reduce_flow_steps", "shorten_response"), p.actions)
        assertFalse(p.pauseNewTurns)
        assertEquals(ThermalPolicy.Level.CRITICAL, p.update(ThermalPolicy.STATUS_SEVERE))
        assertTrue(p.pauseNewTurns)
        assertTrue("pause_new_turns" in p.actions)
    }

    @Test
    fun coolingStepsDownWithHysteresis() {
        val p = ThermalPolicy()
        p.update(ThermalPolicy.STATUS_CRITICAL)
        // Still hot-ish: MODERATE does not clear CRITICAL (needs <= LIGHT).
        assertNull(p.update(ThermalPolicy.STATUS_MODERATE))
        assertEquals(ThermalPolicy.Level.CRITICAL, p.level)
        // LIGHT steps down ONE level, to ELEVATED (not straight to NOMINAL).
        assertEquals(ThermalPolicy.Level.ELEVATED, p.update(ThermalPolicy.STATUS_LIGHT))
        // LIGHT again: ELEVATED needs NONE to clear -> no flap.
        assertNull(p.update(ThermalPolicy.STATUS_LIGHT))
        assertEquals(ThermalPolicy.Level.NOMINAL, p.update(ThermalPolicy.STATUS_NONE))
        assertNull(p.update(ThermalPolicy.STATUS_NONE))
    }

    @Test
    fun aJumpToNoneClearsCriticalInOneUpdate() {
        val p = ThermalPolicy()
        p.update(ThermalPolicy.STATUS_SEVERE)
        // The OS reports changes only; SEVERE -> NONE must not leave the policy stuck at ELEVATED.
        assertEquals(ThermalPolicy.Level.NOMINAL, p.update(ThermalPolicy.STATUS_NONE))
    }

    @Test
    fun reheatingFollowsImmediately() {
        val p = ThermalPolicy()
        p.update(ThermalPolicy.STATUS_MODERATE)
        p.update(ThermalPolicy.STATUS_NONE)
        assertEquals(ThermalPolicy.Level.NOMINAL, p.level)
        assertEquals(ThermalPolicy.Level.CRITICAL, p.update(ThermalPolicy.STATUS_SEVERE))
    }

    @Test
    fun capsRespectTheUserSettingAndTheMeasuredFloors() {
        val p = ThermalPolicy()
        assertEquals(8, p.flowSteps(8))
        assertEquals(120, p.maxResponseTokens(120))
        p.update(ThermalPolicy.STATUS_MODERATE)
        assertEquals(5, p.flowSteps(8))
        assertEquals(5, p.flowSteps(5))
        assertEquals(60, p.maxResponseTokens(120))
        assertEquals(40, p.maxResponseTokens(60))      // never below the floor via halving
        assertEquals(30, p.maxResponseTokens(30))      // never above the user's own setting
        p.update(ThermalPolicy.STATUS_SEVERE)
        assertEquals(40, p.maxResponseTokens(120))
        assertEquals(5, p.flowSteps(6))
    }

    @Test
    fun statusNamesForTheEventLog() {
        assertEquals("MODERATE", ThermalPolicy.statusName(2))
        assertEquals("UNKNOWN(9)", ThermalPolicy.statusName(9))
        assertEquals("elevated", ThermalPolicy.Level.ELEVATED.wire)
    }
}
