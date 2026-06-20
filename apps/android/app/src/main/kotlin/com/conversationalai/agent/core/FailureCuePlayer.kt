package com.conversationalai.agent.core

import android.util.Log
import com.conversationalai.agent.audio.PcmStreamPlayer
import com.conversationalai.agent.tts.ClauseInputBuilder
import com.conversationalai.agent.tts.TtsEngine

/**
 * Plays the short [AudibleFeedback] cues for silent-failure turns. This lives OUTSIDE the
 * controller on purpose: audio I/O (TTS synthesis + PCM writes) belongs to the playback layer, not
 * the orchestrator (the same separation [SpeechTurnRunner] keeps for normal turns). The controller
 * decides *when* a cue is warranted; this decides *how* it is voiced.
 *
 * A spoken cue uses the normal TTS path; if synthesis fails (e.g. the TTS engine itself is what
 * broke), it falls back to a non-speech [AudibleFeedback.earcon] so the user still hears something.
 */
class FailureCuePlayer(
    private val tts: TtsEngine,
    private val inputBuilder: ClauseInputBuilder,
    private val playerFactory: () -> PcmStreamPlayer,
    private val flowSteps: () -> Int,
    private val onPlayerStarted: (PcmStreamPlayer) -> Unit = {},
    private val onPlayerStopped: (PcmStreamPlayer) -> Unit = {},
) {
    /** Speak [cue] in [lang]; fall back to the earcon if synthesis returns nothing. Blocks for the
     *  clip's duration (the caller is on a worker thread and the mic is muted). */
    fun speak(cue: AudibleFeedback.Cue, lang: String, sampleRate: Int) {
        val phrase = AudibleFeedback.phrase(cue, lang)
        val pcm = runCatching {
            tts.synthesizeClause(inputBuilder.build(phrase, lang = LanguageDetector.detect(phrase)), k = flowSteps())
        }.getOrNull() ?: AudibleFeedback.earcon(sampleRate)
        play(pcm)
    }

    /** Play the non-speech earcon directly (used when the TTS path is the suspected failure). */
    fun earcon(sampleRate: Int) = play(AudibleFeedback.earcon(sampleRate))

    private fun play(pcm: FloatArray) {
        val player = playerFactory()
        onPlayerStarted(player)
        runCatching {
            player.start()
            player.write(pcm)
        }.onFailure { Log.w(TAG, "failure-cue playback error: ${it.message}") }
        runCatching { player.stopAndRelease() }
        onPlayerStopped(player)
    }

    private companion object {
        const val TAG = "FailureCuePlayer"
    }
}
