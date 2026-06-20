package com.conversationalai.agent.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Audible cues for the cases where a turn would otherwise end in SILENCE — which on a voice-only
 * device reads as "the assistant froze." Three failure modes get a signal:
 *
 *  - [Cue.NOT_UNDERSTOOD]   the user clearly spoke but ASR produced nothing recognizable;
 *  - [Cue.GENERATION_FAILED] the LLM errored / returned an empty answer (and ran no tool);
 *  - [Cue.PLAYBACK_FAILED]  an answer was generated but every TTS clause failed to synthesize.
 *
 * The first two are spoken with a short, language-matched phrase (the normal TTS path still works);
 * the third — where the TTS engine itself is the thing that broke — uses a synthesized [earcon] so
 * the user still hears *something*. All of this is pure so the rules and the tone are unit-testable.
 */
object AudibleFeedback {

    enum class Cue { NOT_UNDERSTOOD, GENERATION_FAILED, PLAYBACK_FAILED }

    /** Short single-utterance phrase (kept well under the TTS T=64 NFKD budget) for the spoken cues. */
    fun phrase(cue: Cue, lang: String): String = if (lang == "ko") {
        when (cue) {
            Cue.NOT_UNDERSTOOD -> "다시 한 번 말씀해 주세요."
            Cue.GENERATION_FAILED -> "죄송해요, 다시 시도해 주세요."
            Cue.PLAYBACK_FAILED -> "죄송해요, 다시 시도해 주세요."
        }
    } else {
        when (cue) {
            Cue.NOT_UNDERSTOOD -> "Sorry, I didn't catch that."
            Cue.GENERATION_FAILED -> "Sorry, please try again."
            Cue.PLAYBACK_FAILED -> "Sorry, please try again."
        }
    }

    /**
     * Whether a captured utterance that yielded NO recognizable text was probably real speech
     * (worth a "didn't catch that") rather than a brief background-noise VAD trigger. Gating on this
     * keeps the cue from firing on every cough or door slam. Pure: duration + RMS energy only.
     */
    fun looksLikeSpeech(samples: FloatArray, sampleRate: Int): Boolean {
        if (samples.isEmpty() || sampleRate <= 0) return false
        val durationMs = samples.size * 1000L / sampleRate
        if (durationMs < MIN_SPEECH_MS) return false
        var sumSq = 0.0
        for (s in samples) sumSq += s.toDouble() * s
        return sqrt(sumSq / samples.size) >= MIN_SPEECH_RMS
    }

    /**
     * A short, gentle two-tone descending earcon ("I had trouble"), used when even TTS can't speak.
     * Mono float PCM at [sampleRate], peak ~0.22, with per-tone fades so the joins don't click.
     */
    fun earcon(sampleRate: Int): FloatArray {
        val seg = (sampleRate * TONE_MS / 1000).coerceAtLeast(1)
        val out = FloatArray(seg * TONES_HZ.size)
        val fadeN = (seg / 10).coerceAtLeast(1)
        for (t in TONES_HZ.indices) {
            val freq = TONES_HZ[t]
            for (i in 0 until seg) {
                val env = when {
                    i < fadeN -> i.toDouble() / fadeN
                    i > seg - fadeN -> (seg - i).toDouble() / fadeN
                    else -> 1.0
                }
                out[t * seg + i] = (PEAK * env * sin(2.0 * PI * freq * i / sampleRate)).toFloat()
            }
        }
        return out
    }

    const val MIN_SPEECH_MS = 350L
    const val MIN_SPEECH_RMS = 0.008
    private const val TONE_MS = 150
    private const val PEAK = 0.22
    private val TONES_HZ = doubleArrayOf(660.0, 440.0)   // descending, friendly
}
