package com.conversationalai.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechLoopStateMachineTest {
    @Test
    fun allowsHandsFreeHappyPath() {
        val machine = SpeechLoopStateMachine()

        machine.transitionTo(ConvState.LISTENING)
        machine.transitionTo(ConvState.TRANSCRIBING)
        machine.transitionTo(ConvState.GENERATING)
        machine.transitionTo(ConvState.SPEAKING)
        machine.transitionTo(ConvState.LISTENING)

        assertEquals(ConvState.LISTENING, machine.current)
    }

    @Test
    fun allowsTypedTurnHappyPath() {
        val machine = SpeechLoopStateMachine()

        machine.transitionTo(ConvState.GENERATING)
        machine.transitionTo(ConvState.SPEAKING)
        machine.transitionTo(ConvState.IDLE)

        assertEquals(ConvState.IDLE, machine.current)
    }

    @Test
    fun allowsBargeInCancelPath() {
        val machine = SpeechLoopStateMachine()

        machine.transitionTo(ConvState.GENERATING)
        machine.transitionTo(ConvState.CAPTURING)
        machine.transitionTo(ConvState.TRANSCRIBING)
        machine.transitionTo(ConvState.GENERATING)

        assertEquals(ConvState.GENERATING, machine.current)
    }

    @Test
    fun rejectsNonsensicalBackwardTransitions() {
        assertFalse(SpeechLoopStateMachine.canTransition(ConvState.IDLE, ConvState.SPEAKING))
        assertFalse(SpeechLoopStateMachine.canTransition(ConvState.LISTENING, ConvState.SPEAKING))
        assertFalse(SpeechLoopStateMachine.canTransition(ConvState.SPEAKING, ConvState.TRANSCRIBING))
    }

    @Test(expected = IllegalArgumentException::class)
    fun transitionToThrowsOnInvalidTransition() {
        SpeechLoopStateMachine().transitionTo(ConvState.SPEAKING)
    }

    @Test
    fun stopToIdleIsAllowedFromEveryState() {
        for (state in ConvState.entries) {
            assertTrue(SpeechLoopStateMachine.canTransition(state, ConvState.IDLE))
        }
    }
}
