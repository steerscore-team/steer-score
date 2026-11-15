package com.iclab.steerscore.core

private fun SpeedRules.multiplierFor(c: Conditions) = if (c.lowBeam) nightMultiplier else 1f

/** Hard acceleration and hard braking: a speed change of at least the rule's delta within its window. */
class LongitudinalDetector(private val rules: SpeedRules) {

    private val history = ArrayDeque<Pair<Long, Float>>()
    private var cooldownUntilMs = Long.MIN_VALUE

    fun onSample(s: DrivingSample): DrivingEvent? {
        val t = s.timeMs
        history.addLast(t to s.speedKmh)
        // Keep exactly one sample at or before the start of the window as the baseline
        while (history.size > 1 && history[1].first <= t - rules.windowMs) history.removeFirst()

        val (baseTime, baseSpeed) = history.first()
        if (t < cooldownUntilMs || t - baseTime < rules.windowMs / 2) return null

        val delta = s.speedKmh - baseSpeed
        val m = rules.multiplierFor(s.conditions)
        val event = when {
            delta >= rules.accelerationDeltaKmh ->
                HardAcceleration(t, delta, rules.windowMs, rules.accelerationPenalty, m, s.conditions, rules.id)
            -delta >= rules.brakingDeltaKmh ->
                HardBraking(t, -delta, rules.windowMs, rules.brakingPenalty, m, s.conditions, rules.id)
            else -> null
        }
        if (event != null) {
            cooldownUntilMs = t + rules.cooldownMs
            history.clear()
            history.addLast(t to s.speedKmh)
        }
        return event
    }
}

/** Speeding: more than the margin over the posted limit for a sustained period, reported once per episode. */
class SpeedingDetector(private val rules: SpeedRules) {

    private var overSinceMs: Long? = null
    private var reported = false

    fun onSample(s: DrivingSample): DrivingEvent? {
        val limit = s.speedLimitKmh
        val over = if (limit == null) 0f else s.speedKmh - limit
        if (over <= rules.speedingMarginKmh) {
            overSinceMs = null
            reported = false
            return null
        }
        val since = overSinceMs ?: s.timeMs.also { overSinceMs = it }
        if (reported || s.timeMs - since < rules.speedingMinMs) return null
        reported = true
        return Speeding(s.timeMs, over, rules.speedingPenalty, rules.multiplierFor(s.conditions), s.conditions, rules.id)
    }
}
