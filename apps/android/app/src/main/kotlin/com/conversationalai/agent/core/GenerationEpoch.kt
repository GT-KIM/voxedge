package com.conversationalai.agent.core

import java.util.concurrent.atomic.AtomicLong

/** Monotonic cancellation epoch for dropping stale turn work after stop/barge-in/new turns. */
class GenerationEpoch(initialValue: Long = 0L) {
    private val current = AtomicLong(initialValue)

    fun next(): Long = current.incrementAndGet()

    fun cancel(): Long = current.incrementAndGet()

    fun isCurrent(generationId: Long): Boolean = generationId == current.get()
}
