package com.conversationalai.agent.audio

/** Playback boundary for streamed clause PCM. */
interface PcmStreamPlayer {
    fun start()
    fun write(pcm: FloatArray)
    fun interrupt()
    fun stopAndRelease()
}
