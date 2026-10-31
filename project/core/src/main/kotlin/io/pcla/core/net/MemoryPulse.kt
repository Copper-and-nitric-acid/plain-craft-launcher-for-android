package io.pcla.core.net

/** Heap shape verdicts. The constant names are user-facing shorthand (see MemoryPulse). */
enum class Verdict { 正常, 便秘, 拉稀 }

data class Pulse(val verdict: Verdict, val detail: String)

/**
 * Heap shape analysis: distinguish a slow leak from allocation churn.
 *
 * - leak (enum constant 便秘): usage stays pinned near the ceiling and does not come back down
 *   after GC — the classic shape of a leak;
 * - churn (enum constant 拉稀): usage swings by tens of MB within a very short time — not a leak,
 *   but allocation and reclamation happening too often, which shows up as GC jitter and
 *   sawtooth frame times.
 *
 * The two need opposite fixes (resize the heap vs. reduce allocation), so they are classified
 * separately. Input: the latest few evenly spaced samples (MB) and the heap cap.
 */
object MemoryPulse {

    /** Churn threshold: 3% of the heap cap, never below 16MB. */
    private fun churnThreshold(maxMb: Int): Int = maxOf(16, maxMb * 3 / 100)

    fun classify(samplesMb: List<Int>, maxMb: Int): Pulse {
        if (samplesMb.size < 3) return Pulse(Verdict.正常, "采样太少（${samplesMb.size} 个），再等等")
        val deltas = samplesMb.zipWithNext { a, b -> kotlin.math.abs(b - a) }
        val avgDelta = deltas.average()
        val last = samplesMb.last()
        val lo = samplesMb.min()
        val hi = samplesMb.max()
        val tension = if (maxMb > 0) last * 100 / maxMb else 0

        val churn = churnThreshold(maxMb)
        return when {
            avgDelta >= churn ->
                Pulse(Verdict.拉稀, "内存变化太快：每次平均抖 ${"%.0f".format(avgDelta)}MB（阈值 ${churn}MB），GC 在追着跑")
            tension >= 85 && hi - lo <= maxMb * 2 / 100 ->
                Pulse(Verdict.便秘, "占用 ${last}MB / ${maxMb}MB（$tension%）居高不下，回收不动，像泄漏")
            tension >= 85 ->
                Pulse(Verdict.正常, "占用偏高（$tension%）但还在回落，先不动它")
            else ->
                Pulse(Verdict.正常, "占用 ${last}MB / ${maxMb}MB（$tension%），波动 ${hi - lo}MB")
        }
    }
}
