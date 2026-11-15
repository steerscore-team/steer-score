package com.iclab.steerscore.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Lateral acceleration (m/s², left negative) from speed and steering wheel angle, using the bicycle model
 * a = v² · tan(δ) / (L + K·v²) with δ = steering wheel angle / steering ratio and K the understeer term
 * (0 = kinematic). Clamped to [AnalyzerConfig.maxLateral], since tire grip caps what a real car can reach.
 */
fun lateralAcceleration(speedKmh: Float, steeringDeg: Float, cfg: AnalyzerConfig): Float {
    val v = speedKmh / 3.6
    val a = (v * v * tan(roadWheelRad(steeringDeg, cfg)) / effectiveWheelbase(v, cfg)).toFloat()
    return a.coerceIn(-cfg.maxLateral, cfg.maxLateral)
}

/** Yaw rate (degrees per second, left negative) from the same model: r = v · tan(δ) / (L + K·v²). */
fun yawRateDegS(speedKmh: Float, steeringDeg: Float, cfg: AnalyzerConfig): Float {
    val v = speedKmh / 3.6
    return Math.toDegrees(v * tan(roadWheelRad(steeringDeg, cfg)) / effectiveWheelbase(v, cfg)).toFloat()
}

private fun roadWheelRad(steeringDeg: Float, cfg: AnalyzerConfig) = Math.toRadians((steeringDeg / cfg.steeringRatio).toDouble())

private fun effectiveWheelbase(v: Double, cfg: AnalyzerConfig) = cfg.wheelbaseM + cfg.understeerK * v * v

private fun signOf(x: Float) = if (x < 0) -1 else 1

/**
 * How far (m) the car got toward the side it first moved to, over consecutive shapes that together end parallel
 * to the start. A shape only starts recording once the lateral acceleration crosses the enter threshold, so the
 * onset of the first steer is missing; that would leave the integrated heading slightly off and make the car seem
 * to drift on to the other side. As with [SShape.offsetM], the constant error is removed first, which a maneuver
 * ending parallel allows, and only the excursion toward [direction] counts.
 */
private fun peakExcursionM(direction: Int, vararg shapes: SShape): Float {
    var weighted = 0.0
    var total = 0.0
    for (m in shapes) for (i in m.deviations.indices) {
        weighted += m.deviations[i] * m.steps[i]
        total += m.steps[i]
    }
    val bias = if (total > 0) weighted / total else 0.0
    var heading = 0.0
    var y = 0.0
    var peak = 0.0
    for (m in shapes) {
        for (i in m.deviations.indices) {
            val v = m.speeds[i]
            if (v <= 0.5f) continue
            heading += (m.deviations[i] - bias) / v * m.steps[i]
            y += v * sin(heading) * m.steps[i]
            peak = max(peak, y * direction)
        }
    }
    return peak.toFloat()
}

/**
 * One S-shaped maneuver in lateral acceleration measured from a reference level [ref]: zero on a straight
 * road, or the curve's own level when the maneuver happens on a bend. Along the way it integrates how far
 * the car turned and moved sideways relative to that reference, which is what separates a lane change
 * (ends parallel, about a lane to the side) from a wobble or a turn.
 */
private class SShape(
    val ref: Float,
    private val cfg: AnalyzerConfig,
    t: Long,
    sign: Int,
    mag: Float,
    /**
     * On a bend the reference is only an estimate of the curve's level, so the maneuver may also end where
     * the steering settles back near it rather than exactly on it.
     */
    private val onCurve: Boolean = false,
) {

    enum class Step { CONTINUE, COMPLETED, WEAVE, SETTLED_ONE_SIDED, FIRST_TOO_LONG, SECOND_TOO_LONG }

    /** Sign of the first half: -1 left, +1 right. */
    val direction = sign
    val startMs = t
    var peak = mag
        private set
    var headingDeg = 0f
        private set
    /** End of the lane change, valid after [Step.COMPLETED] or [Step.WEAVE]. */
    var endMs = t
        private set

    /** True once the counter-steer half has started. */
    var second = false
        private set
    /** When the counter-steer half started. */
    var secondStartMs = t
        private set
    private var phaseStartMs = t
    private var belowSinceMs: Long? = null
    private var lastMs = t
    // Per-sample deviation from [ref] (m/s²), time step (s) and speed (m/s), for integrating heading and offset
    val deviations = ArrayList<Float>()
    val steps = ArrayList<Float>()
    val speeds = ArrayList<Float>()
    private val recent = ArrayDeque<Float>()

    /**
     * Sideways movement over the maneuver. A lane change ends parallel to where it started, so any constant
     * error in the reference (a curve's level is only estimated) is removed first; otherwise it would grow
     * quadratically in the double integration.
     */
    val offsetM: Float
        get() {
            if (deviations.isEmpty()) return 0f
            val bias = deviations.sum() / deviations.size
            var heading = 0.0
            var y = 0.0
            for (i in deviations.indices) {
                val v = speeds[i]
                if (v <= 0.5f) continue
                heading += (deviations[i] - bias) / v * steps[i]
                y += v * sin(heading) * steps[i]
            }
            return y.toFloat()
        }

    fun step(t: Long, a: Float, speedKmh: Float): Step {
        val d = a - ref
        val v = speedKmh / 3.6f
        val dt = (t - lastMs).coerceIn(0L, 1000L) / 1000f
        lastMs = t
        if (v > 0.5f) headingDeg += Math.toDegrees((d / v).toDouble()).toFloat() * dt
        deviations += d
        steps += dt
        speeds += v
        recent.addLast(a)
        if (recent.size > STEADY_SAMPLES) recent.removeFirst()

        val mag = abs(d)
        val sign = signOf(d)
        peak = max(peak, mag)
        if (!second) {
            return when {
                sign == -direction && mag >= cfg.lateralEnter -> {
                    second = true
                    secondStartMs = t
                    phaseStartMs = t
                    belowSinceMs = null
                    Step.CONTINUE
                }
                t - phaseStartMs > cfg.maxPhaseMs -> Step.FIRST_TOO_LONG
                // A slow lane change lingers near zero on its way to the other side, so a one-sided
                // maneuver is only dropped after a longer quiet spell than it takes to end one
                settled(t, mag, cfg.firstHalfSettleMs) -> Step.SETTLED_ONE_SIDED
                else -> Step.CONTINUE
            }
        }
        return when {
            // Steering straight into the next lane change without settling, as when weaving through traffic
            sign == direction && mag >= cfg.lateralEnter -> {
                endMs = t
                Step.WEAVE
            }
            t - phaseStartMs > (if (onCurve) cfg.maxPhaseMs else cfg.maxReturnPhaseMs) -> Step.SECOND_TOO_LONG
            settled(t, mag) -> {
                endMs = belowSinceMs ?: t
                Step.COMPLETED
            }
            onCurve && mag < cfg.lateralEnter && steady() -> {
                endMs = t - STEADY_SAMPLES * 100L
                Step.COMPLETED
            }
            else -> Step.CONTINUE
        }
    }

    /** The lateral acceleration has stopped changing for the last half second. */
    private fun steady(): Boolean =
        recent.size == STEADY_SAMPLES && recent.max() - recent.min() < STEADY_SPREAD

    private companion object {
        const val STEADY_SAMPLES = 6
        const val STEADY_SPREAD = 0.15f
    }

    /** True once the deviation has stayed below the exit threshold for [forMs]. */
    private fun settled(t: Long, mag: Float, forMs: Long = cfg.settleMs): Boolean {
        if (mag >= cfg.lateralExit) {
            belowSinceMs = null
            return false
        }
        val since = belowSinceMs ?: t.also { belowSinceMs = it }
        return t - since >= forMs
    }
}

/**
 * Detects lane changes and curves from the steering signal at road speeds.
 *
 * - **Lane change:** lateral acceleration to one side, then the other, then back, ending roughly parallel
 *   to the start (heading change ≤ 20°) and shifted sideways by at least 1.5 m.
 * - **Curve:** steering held to one side for longer than [AnalyzerConfig.maxPhaseMs]. While on a curve, the
 *   curve's own lateral acceleration is tracked and subtracted, so a lane change on the bend still shows up
 *   as an S-shape around it. A lane change that steers out of the curve to the other side is tracked
 *   against zero in parallel; whichever reading completes first wins.
 *
 * Below [AnalyzerConfig.minLateralSpeedKmh] nothing is judged here; intersection turns are left to
 * [TurnDetector].
 */
class LateralDetector(private val cfg: AnalyzerConfig) {

    private enum class Phase { IDLE, MANEUVER, CURVE }

    private var phase = Phase.IDLE
    /** Maneuver measured from zero, on a straight road. */
    private var primary: SShape? = null
    /**
     * A lane change that ran straight into the next steering without settling. It is held until that steering
     * resolves: another S-shape means weaving (both count), a one-sided straightening means the car went out
     * and came straight back.
     */
    private var pending: Pending? = null

    /** [lane] is null when the shape moved too little to be a lane change, but may still be a drift over the line. */
    private class Pending(val shape: SShape, val lane: LaneChange?)

    // The curve being followed
    private var curveDir = 0
    private var curveStartMs = 0L
    private var curveHeadingDeg = 0f
    private var curvePeak = 0f
    private var curveSpeedSum = 0.0
    private var curveSamples = 0
    private var curveBaseline = 0f
    private var curveBelowSinceMs: Long? = null
    /** Lane change measured from the curve's own level. */
    private var onCurve: SShape? = null
    /** Steering out of the curve to the other side, measured from zero. */
    private var outOfCurve: SShape? = null

    private var lastMs: Long? = null
    private var filteredSteering: Float? = null
    private var lastLeftSignalMs = Long.MIN_VALUE / 2
    private var lastRightSignalMs = Long.MIN_VALUE / 2

    var lateralAcc = 0f
        private set
    val smoothedSteering: Float get() = filteredSteering ?: 0f

    /** Returns the events completed on this sample, usually none. */
    fun onSample(s: DrivingSample): List<DrivingEvent> {
        when (s.turnSignal) {
            Turn.LEFT -> lastLeftSignalMs = s.timeMs
            Turn.RIGHT -> lastRightSignalMs = s.timeMs
            Turn.OFF -> Unit
        }
        val steering = filteredSteering?.let { it + cfg.steeringSmoothing * (s.steeringDeg - it) } ?: s.steeringDeg
        filteredSteering = steering
        lateralAcc = lateralAcceleration(s.speedKmh, steering, cfg)

        val t = s.timeMs
        val dtMs = lastMs?.let { (t - it).coerceIn(0L, 1000L) } ?: 0L
        lastMs = t

        if (s.speedKmh < cfg.minLateralSpeedKmh) {
            val held = flushPending()
            reset()
            return held
        }

        val a = lateralAcc
        return when (phase) {
            Phase.IDLE -> {
                if (abs(a) >= cfg.lateralEnter) {
                    primary = SShape(0f, cfg, t, signOf(a), abs(a))
                    phase = Phase.MANEUVER
                }
                emptyList()
            }
            Phase.MANEUVER -> onManeuver(s, a)
            Phase.CURVE -> onCurveSample(s, a, dtMs)
        }
    }

    private fun onManeuver(s: DrivingSample, a: Float): List<DrivingEvent> {
        val m = primary ?: return emptyList()
        return when (m.step(s.timeMs, a, s.speedKmh)) {
            SShape.Step.CONTINUE -> emptyList()
            SShape.Step.COMPLETED -> {
                val held = flushPending()
                reset()
                held + listOfNotNull(laneChange(m, s, onCurve = false))
            }
            SShape.Step.WEAVE -> {
                val held = flushPending()
                primary = SShape(0f, cfg, s.timeMs, m.direction, abs(a))
                val lane = laneChange(m, s, onCurve = false) as? LaneChange
                if (lane != null || abs(m.headingDeg) <= cfg.maxLaneChangeHeadingDeg) pending = Pending(m, lane)
                held
            }
            SShape.Step.SETTLED_ONE_SIDED -> {
                val held = pending
                pending = null
                reset()
                when {
                    // Out and straight back: the merged middle lobe belonged to two S-shapes
                    held != null && m.direction == held.shape.direction -> outAndBack(held.shape, held.lane, m, s)
                    // One-sided steering that relaxes without a counter-steer is a small correction
                    else -> listOfNotNull(held?.lane)
                }
            }
            SShape.Step.FIRST_TOO_LONG, SShape.Step.SECOND_TOO_LONG -> {
                val held = flushPending()
                enterCurve(m, a)
                held
            }
        }
    }

    private fun flushPending(): List<DrivingEvent> = listOfNotNull(pending?.lane).also { pending = null }

    /**
     * Right, left-left, right (or mirrored): the car moved over and came straight back, ending parallel. Within
     * the lane that is a weave (nothing); over the line, a [LaneDeparture]; a whole lane over, a lane change and
     * one back. [out] is the first half judged on its own, or null if it moved too little to be a lane change.
     */
    private fun outAndBack(first: SShape, out: LaneChange?, trailing: SShape, s: DrivingSample): List<DrivingEvent> {
        // Not back to parallel: not an out-and-back, keep the first judgment
        if (abs(first.headingDeg + trailing.headingDeg) > cfg.maxLaneChangeHeadingDeg) return listOfNotNull(out)
        val excursion = peakExcursionM(first.direction, first, trailing)
        if (excursion < cfg.minDepartureOffsetM) return emptyList()
        // The trailing shape ended after a quiet spell of firstHalfSettleMs
        val endMs = s.timeMs - cfg.firstHalfSettleMs
        val dir = if (first.direction < 0) Turn.LEFT else Turn.RIGHT
        if (excursion < cfg.laneReturnOffsetM) {
            return listOf(
                LaneDeparture(
                    timeMs = first.startMs,
                    direction = dir,
                    durationMs = endMs - first.startMs,
                    peakOffsetM = excursion,
                    peakLateral = max(first.peak, trailing.peak),
                    basePenalty = cfg.penalties.laneDeparture,
                    multiplier = s.conditions.riskMultiplier(),
                    conditions = s.conditions,
                )
            )
        }
        // The way back starts halfway through the long middle lobe
        val backStart = (first.secondStartMs + first.endMs) / 2
        val back = if (dir == Turn.LEFT) Turn.RIGHT else Turn.LEFT
        return listOf(
            laneChangeLeg(first.startMs, dir, backStart - first.startMs, first.peak, excursion, s),
            laneChangeLeg(backStart, back, endMs - backStart, max(first.peak, trailing.peak), excursion, s),
        )
    }

    /** One leg of an out-and-back as a lane change, judged like [laneChange]. */
    private fun laneChangeLeg(startMs: Long, turn: Turn, durationMs: Long, peak: Float, offsetM: Float, s: DrivingSample): LaneChange {
        val lastSignal = if (turn == Turn.LEFT) lastLeftSignalMs else lastRightSignalMs
        val signaled = lastSignal >= startMs - cfg.signalLookbackMs
        val abrupt = peak >= cfg.abruptLateral || (durationMs < cfg.quickLaneChangeMs && peak >= cfg.quickLaneChangeLateral)
        var base = 0f
        if (abrupt) base += cfg.penalties.abruptLaneChange
        if (!signaled) base += cfg.penalties.unsignaledLaneChange
        return LaneChange(
            timeMs = startMs,
            direction = turn,
            durationMs = durationMs,
            peakLateral = peak,
            signaled = signaled,
            abrupt = abrupt,
            basePenalty = base,
            multiplier = s.conditions.riskMultiplier(),
            conditions = s.conditions,
            headingChangeDeg = 0f,
            lateralOffsetM = offsetM,
        )
    }

    private fun enterCurve(from: SShape, a: Float) {
        phase = Phase.CURVE
        primary = null
        curveDir = signOf(a)
        curveStartMs = from.startMs
        curveHeadingDeg = from.headingDeg
        curvePeak = abs(a).coerceAtLeast(from.peak)
        curveSpeedSum = 0.0
        curveSamples = 0
        curveBaseline = a
        curveBelowSinceMs = null
        onCurve = null
        outOfCurve = null
    }

    private fun onCurveSample(s: DrivingSample, a: Float, dtMs: Long): List<DrivingEvent> {
        val t = s.timeMs
        val v = s.speedKmh / 3.6f
        curveHeadingDeg += Math.toDegrees((a / v).toDouble()).toFloat() * dtMs / 1000f
        curveSpeedSum += s.speedKmh
        curveSamples++

        val events = mutableListOf<DrivingEvent>()

        // A lane change on the bend: an S-shape around the curve's own level
        val oc = onCurve
        if (oc == null && outOfCurve == null) curvePeak = max(curvePeak, abs(a))
        if (oc == null) {
            // Follow the bend closely while the steering sits on it, but only slowly once it starts to leave,
            // so the start of a lane change does not drag the reference along with it
            val d0 = a - curveBaseline
            val tau = if (abs(d0) < cfg.lateralExit) cfg.curveBaselineMs else cfg.curveBaselineMs * 4
            curveBaseline += d0 * dtMs / (tau + dtMs).toFloat()
            val d = a - curveBaseline
            if (abs(d) >= cfg.lateralEnter) onCurve = SShape(curveBaseline, cfg, t, signOf(d), abs(d), onCurve = true)
        } else {
            when (oc.step(t, a, s.speedKmh)) {
                SShape.Step.COMPLETED -> {
                    laneChange(oc, s, onCurve = true)?.let { events += it }
                    onCurve = null
                    outOfCurve = null
                }
                SShape.Step.WEAVE -> {
                    laneChange(oc, s, onCurve = true)?.let { events += it }
                    onCurve = SShape(oc.ref, cfg, t, oc.direction, abs(a - oc.ref), onCurve = true)
                    outOfCurve = null
                }
                SShape.Step.CONTINUE -> Unit
                // Not a lane change after all (the bend tightened or eased): re-sync to where the road is now
                else -> {
                    onCurve = null
                    curveBaseline = a
                }
            }
        }

        // Steering out of the curve to the other side: the bend ended, possibly straight into a lane change
        val oo = outOfCurve
        if (oo == null) {
            if (events.isEmpty() && signOf(a) == -curveDir && abs(a) >= cfg.lateralEnter) {
                outOfCurve = SShape(0f, cfg, t, signOf(a), abs(a))
            }
        } else {
            when (oo.step(t, a, s.speedKmh)) {
                SShape.Step.COMPLETED -> {
                    finishCurve(s)?.let { events += it }
                    laneChange(oo, s, onCurve = false)?.let { events += it }
                    reset()
                    return events
                }
                SShape.Step.WEAVE -> {
                    finishCurve(s)?.let { events += it }
                    laneChange(oo, s, onCurve = false)?.let { events += it }
                    reset()
                    primary = SShape(0f, cfg, t, oo.direction, abs(a))
                    phase = Phase.MANEUVER
                    return events
                }
                // Held to the other side: the road bends the other way (an S-bend)
                SShape.Step.FIRST_TOO_LONG -> {
                    finishCurve(s)?.let { events += it }
                    reset()
                    enterCurve(oo, a)
                    return events
                }
                SShape.Step.CONTINUE -> Unit
                else -> outOfCurve = null
            }
        }

        // Back to straight: the curve is over. The counter-steer half of a lane change toward the inside of
        // the bend also passes near zero, so it does not count while that half is under way.
        val midLaneChange = onCurve?.second == true
        if (abs(a) < cfg.lateralExit && !midLaneChange) {
            val since = curveBelowSinceMs ?: t.also { curveBelowSinceMs = it }
            if (t - since >= cfg.curveEndSettleMs) {
                finishCurve(s, endMs = since)?.let { events += it }
                reset()
            }
        } else {
            curveBelowSinceMs = null
        }
        return events
    }

    /** The curve as an event, or null when it was really an intersection turn (left to [TurnDetector]). */
    private fun finishCurve(s: DrivingSample, endMs: Long = s.timeMs): DrivingEvent? {
        val meanSpeed = if (curveSamples == 0) s.speedKmh.toDouble() else curveSpeedSum / curveSamples
        if (abs(curveHeadingDeg) >= cfg.turnMinHeadingDeg && meanSpeed <= cfg.turnMaxSpeedKmh) return null
        val hard = curvePeak >= cfg.hardCornerLateral
        return Cornering(
            timeMs = curveStartMs,
            direction = if (curveDir < 0) Turn.LEFT else Turn.RIGHT,
            durationMs = endMs - curveStartMs,
            headingChangeDeg = curveHeadingDeg,
            peakLateral = curvePeak,
            hard = hard,
            basePenalty = if (hard) cfg.penalties.hardCornering else 0f,
            multiplier = s.conditions.riskMultiplier(),
            conditions = s.conditions,
        )
    }

    /** The maneuver as a lane change, or null when it was too long, turned too far, or barely moved sideways. */
    private fun laneChange(m: SShape, s: DrivingSample, onCurve: Boolean): DrivingEvent? {
        val duration = m.endMs - m.startMs
        if (duration > cfg.maxLaneChangeMs) return null
        if (abs(m.headingDeg) > cfg.maxLaneChangeHeadingDeg) return null
        if (abs(m.offsetM) < cfg.minLaneChangeOffsetM) return null

        val turn = if (m.direction < 0) Turn.LEFT else Turn.RIGHT
        val lastSignal = if (turn == Turn.LEFT) lastLeftSignalMs else lastRightSignalMs
        val signaled = lastSignal >= m.startMs - cfg.signalLookbackMs
        val abrupt = m.peak >= cfg.abruptLateral ||
            (duration < cfg.quickLaneChangeMs && m.peak >= cfg.quickLaneChangeLateral)

        var base = 0f
        if (abrupt) base += cfg.penalties.abruptLaneChange
        if (!signaled) base += cfg.penalties.unsignaledLaneChange
        return LaneChange(
            timeMs = m.startMs,
            direction = turn,
            durationMs = duration,
            peakLateral = m.peak,
            signaled = signaled,
            abrupt = abrupt,
            basePenalty = base,
            multiplier = s.conditions.riskMultiplier(),
            conditions = s.conditions,
            headingChangeDeg = m.headingDeg,
            lateralOffsetM = abs(m.offsetM),
            onCurve = onCurve,
        )
    }

    private fun reset() {
        phase = Phase.IDLE
        primary = null
        pending = null
        onCurve = null
        outOfCurve = null
        curveBelowSinceMs = null
    }
}
