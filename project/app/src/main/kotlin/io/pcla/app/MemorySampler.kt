package io.pcla.app

import io.pcla.core.net.MemoryPulse
import io.pcla.core.net.Pulse

/**
 * Sampling side of the memory health check.
 *
 * Reading used/max off `Runtime` is enough to tell a leak (memory never reclaimed) from
 * allocation churn (constant allocate/free): the difference is a **shape over time**, not an
 * absolute value at one instant, so several points must be collected before classifying.
 *
 * When memory moves fast (typically right after entering a world with bulk chunk loading), the
 * once-a-second sampling here shows heavy jitter — and that jitter is exactly what the churn
 * check watches: jitter alone is harmless, jitter the GC cannot keep up with is not.
 */
object MemorySampler {

    data class Snapshot(val usedMb: Int, val maxMb: Int, val pulse: Pulse)

    private const val WINDOW = 8

    private val samples = ArrayDeque<Int>()

    fun reset() = samples.clear()

    fun sample(): Snapshot {
        val rt = Runtime.getRuntime()
        val usedMb = ((rt.totalMemory() - rt.freeMemory()) / 1048576L).toInt()
        val maxMb = (rt.maxMemory() / 1048576L).toInt()
        samples.addLast(usedMb)
        while (samples.size > WINDOW) samples.removeFirst()
        return Snapshot(usedMb, maxMb, MemoryPulse.classify(samples.toList(), maxMb))
    }

    /**
     * Reset the sample window at launch.
     *
     * `System.gc()` is deliberately not called (it would be a disaster in a game process); this
     * only clears the samples so the previous session's high-water mark does not skew the
     * classification of this one.
     */
    fun markLaunchStart() = reset()
}
