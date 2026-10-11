package dev.peytonli.tapdrop.core

import kotlin.math.abs

data class TapConfig(
    val deltaUt: Float = 150f,
    val absoluteUt: Float = 250f,
    val rearmMs: Long = 1000,
    val bumpMs2: Float = 2.5f,
    val windowMs: Long = 350,
    val cooldownMs: Long = 3000,
    val baselineWindowMs: Long = 2000,
) {
    init {
        require(deltaUt >= 0 && deltaUt.isFinite())
        require(absoluteUt >= 0 && absoluteUt.isFinite())
        require(rearmMs >= 0)
        require(bumpMs2 >= 0 && bumpMs2.isFinite())
        require(windowMs >= 0)
        require(cooldownMs >= 0)
        require(baselineWindowMs > 0)
    }
}

data class TapTrigger(
    val timestampNanos: Long,
    val magnitudeUt: Float,
    val baselineUt: Float,
    val linearMagnitudeMs2: Float,
)

data class TapDetectorState(
    val magnitudeUt: Float = 0f,
    val baselineUt: Float = 0f,
    val near: Boolean = false,
    val linearMagnitudeMs2: Float = 0f,
    val lastTrigger: TapTrigger? = null,
)

class TapDetector(config: TapConfig = TapConfig()) {
    private var config = config
    private val baselineSamples = ArrayDeque<Reading>()
    private val bumpSamples = mutableListOf<Reading>()
    private var near = false
    private var farSinceNanos: Long? = null
    private var pendingEdge: NearEdge? = null
    private var lastTriggerNanos: Long? = null

    var state = TapDetectorState()
        private set

    fun updateConfig(config: TapConfig) {
        this.config = config
    }

    fun onMagnetic(tNanos: Long, magnitudeUt: Float): TapTrigger? {
        require(magnitudeUt >= 0 && magnitudeUt.isFinite())
        val baseline = medianAt(tNanos) ?: 0f
        val nearNow = magnitudeUt - baseline > config.deltaUt || magnitudeUt > config.absoluteUt

        if (nearNow && !near) {
            val farDuration = farSinceNanos?.let { tNanos - it }
            pendingEdge = if (farDuration != null && farDuration >= millisToNanos(config.rearmMs))
                NearEdge(tNanos, magnitudeUt, baseline)
            else null
        } else if (!nearNow) {
            if (near) {
                farSinceNanos = tNanos
                pendingEdge = null
            } else if (farSinceNanos == null) {
                farSinceNanos = tNanos
            }
            baselineSamples.addLast(Reading(tNanos, magnitudeUt))
        }
        near = nearNow
        state = state.copy(
            magnitudeUt = magnitudeUt,
            baselineUt = medianAt(tNanos) ?: 0f,
            near = near,
        )
        pruneBumps(tNanos)
        val edge = pendingEdge
        if (edge != null) {
            val bump = matchingBump(edge)
            if (bump != null) return fire(edge, bump)
            if (tNanos - edge.timestampNanos > millisToNanos(config.windowMs)) pendingEdge = null
        }
        return null
    }

    fun onAccel(tNanos: Long, linearMagnitudeMs2: Float): TapTrigger? {
        require(linearMagnitudeMs2 >= 0 && linearMagnitudeMs2.isFinite())
        state = state.copy(linearMagnitudeMs2 = linearMagnitudeMs2)
        if (linearMagnitudeMs2 > config.bumpMs2) bumpSamples.add(Reading(tNanos, linearMagnitudeMs2))
        pruneBumps(tNanos)
        val edge = pendingEdge ?: return null
        if (tNanos - edge.timestampNanos > millisToNanos(config.windowMs)) {
            pendingEdge = null
            return null
        }
        val bump = matchingBump(edge) ?: return null
        return fire(edge, bump)
    }

    private fun medianAt(tNanos: Long): Float? {
        val cutoff = tNanos - millisToNanos(config.baselineWindowMs)
        while (baselineSamples.firstOrNull()?.timestampNanos?.let { it < cutoff } == true)
            baselineSamples.removeFirst()
        if (baselineSamples.isEmpty()) return null
        val values = baselineSamples.map { it.value }.sorted()
        val middle = values.size / 2
        return if (values.size % 2 == 0) (values[middle - 1] + values[middle]) / 2f else values[middle]
    }

    private fun pruneBumps(tNanos: Long) {
        val cutoff = tNanos - millisToNanos(config.windowMs)
        bumpSamples.removeAll { it.timestampNanos < cutoff }
        if (bumpSamples.size > MAX_BUMP_SAMPLES)
            bumpSamples.subList(0, bumpSamples.size - MAX_BUMP_SAMPLES).clear()
    }

    private fun matchingBump(edge: NearEdge): Reading? =
        bumpSamples.firstOrNull { abs(it.timestampNanos - edge.timestampNanos) <= millisToNanos(config.windowMs) }

    private fun fire(edge: NearEdge, bump: Reading): TapTrigger? {
        pendingEdge = null
        val timestamp = maxOf(edge.timestampNanos, bump.timestampNanos)
        val previous = lastTriggerNanos
        if (previous != null && timestamp - previous < millisToNanos(config.cooldownMs)) return null
        lastTriggerNanos = timestamp
        val trigger = TapTrigger(timestamp, edge.magnitudeUt, edge.baselineUt, bump.value)
        state = state.copy(lastTrigger = trigger)
        return trigger
    }

    private data class Reading(val timestampNanos: Long, val value: Float)
    private data class NearEdge(val timestampNanos: Long, val magnitudeUt: Float, val baselineUt: Float)

    private companion object {
        const val MAX_BUMP_SAMPLES = 64
        fun millisToNanos(milliseconds: Long): Long = milliseconds * 1_000_000L
    }
}
