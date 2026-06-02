package com.conversationalai.agent.core

/** Small transition guard for the speech loop statechart. */
class SpeechLoopStateMachine(initialState: ConvState = ConvState.IDLE) {
    @Volatile
    var current: ConvState = initialState
        private set

    fun transitionTo(next: ConvState): ConvState {
        val prev = current
        require(canTransition(prev, next)) { "invalid speech-loop transition: $prev -> $next" }
        current = next
        return next
    }

    companion object {
        fun canTransition(from: ConvState, to: ConvState): Boolean {
            if (from == to) return true
            if (to == ConvState.IDLE || to == ConvState.RECOVERING) return true
            return when (from) {
                ConvState.IDLE -> to == ConvState.LISTENING || to == ConvState.GENERATING
                ConvState.LISTENING -> to == ConvState.CAPTURING || to == ConvState.TRANSCRIBING
                ConvState.CAPTURING -> to == ConvState.TRANSCRIBING || to == ConvState.GENERATING
                ConvState.TRANSCRIBING -> to == ConvState.GENERATING || to == ConvState.LISTENING
                ConvState.GENERATING -> to == ConvState.SPEAKING || to == ConvState.CAPTURING || to == ConvState.LISTENING
                ConvState.SPEAKING -> to == ConvState.LISTENING || to == ConvState.CAPTURING
                ConvState.RECOVERING -> to == ConvState.LISTENING
            }
        }
    }
}
