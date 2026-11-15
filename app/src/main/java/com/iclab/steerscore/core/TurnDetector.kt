package com.iclab.steerscore.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Detects intersection turns and U-turns by how far the car actually turned.
 *
 * An episode starts when the yaw rate (from steering and speed) passes [AnalyzerConfig.turnEnterYawDegS]
 * and ends once it stays below [AnalyzerConfig.turnExitYawDegS]. Integrating the yaw rate over the episode
 * gives the heading change: about 90° for a turn, about 180° for a U-turn, and about 0° for a lane change,
 * whose two halves cancel. Episodes faster than [AnalyzerConfig.turnMaxSpeedKmh] on average are bends or
 * ramps, which [LateralDetector] reports as curves.
 */
class TurnDetector(private val cfg: AnalyzerConfig) {

    private var active = false
    private var startMs = 0L
    private var headingDeg = 0f
    private var peak = 0f
    private var speedSum = 0.0
    private var movingSamples = 0
    private var belowSinceMs: Long? = null
    private var lastMs: Long? = null
    private var lastLeftSignalMs = Long.MIN_VALUE / 2
    private var lastRightSignalMs = Long.MIN_VALUE / 2

    /** [steeringDeg] and [lateral] are the smoothed values [LateralDetector] computed for this sample. */
    fun onSample(s: DrivingSample, steeringDeg: Float, lateral: Float): DrivingEvent? {
        when (s.turnSignal) {
            Turn.LEFT -> lastLeftSignalMs = s.timeMs
            Turn.RIGHT -> lastRightSignalMs = s.timeMs
            Turn.OFF -> Unit
        }
        val t = s.timeMs
        val dt = lastMs?.let { (t - it).coerceIn(0L, 1000L) / 1000f } ?: 0f
        lastMs = t

        val yaw = yawRateDegS(s.speedKmh, steeringDeg, cfg)
        val moving = s.speedKmh >= cfg.minTurnSpeedKmh
        if (!active) {
            if (!moving || abs(yaw) < cfg.turnEnterYawDegS) return null
            active = true
            startMs = t
            headingDeg = 0f
            peak = 0f
            speedSum = 0.0
            movingSamples = 0
            belowSinceMs = null
        }

        headingDeg += yaw * dt
        peak = max(peak, abs(lateral))
        if (t - startMs > cfg.turnMaxDurationMs) {
            active = false
            return null
        }
        // Waiting for pedestrians halfway through a turn does not end it
        if (!moving) {
            belowSinceMs = null
            return null
        }
        speedSum += s.speedKmh
        movingSamples++

        if (abs(yaw) >= cfg.turnExitYawDegS) {
            belowSinceMs = null
            return null
        }
        val since = belowSinceMs ?: t.also { belowSinceMs = it }
        return if (t - since >= cfg.turnSettleMs) finish(s, endMs = since) else null
    }

    private fun finish(s: DrivingSample, endMs: Long): DrivingEvent? {
        active = false
        val meanSpeed = if (movingSamples == 0) 0.0 else speedSum / movingSamples
        if (abs(headingDeg) < cfg.turnMinHeadingDeg || meanSpeed > cfg.turnMaxSpeedKmh) return null

        val direction = if (headingDeg < 0) Turn.LEFT else Turn.RIGHT
        val lastSignal = if (direction == Turn.LEFT) lastLeftSignalMs else lastRightSignalMs
        val signaled = lastSignal >= startMs - cfg.turnSignalLookbackMs
        val hard = peak >= cfg.hardCornerLateral

        var base = 0f
        if (!signaled) base += cfg.penalties.unsignaledTurn
        if (hard) base += cfg.penalties.hardCornering
        return IntersectionTurn(
            timeMs = startMs,
            direction = direction,
            uTurn = abs(headingDeg) >= cfg.uTurnHeadingDeg,
            durationMs = endMs - startMs,
            headingChangeDeg = headingDeg,
            peakLateral = peak,
            signaled = signaled,
            hard = hard,
            basePenalty = base,
            multiplier = s.conditions.riskMultiplier(),
            conditions = s.conditions,
        )
    }
}
