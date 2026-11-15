package com.iclab.steerscore.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** A ground-truth maneuver of a synthetic drive, in sample indices (10 Hz), end exclusive. */
data class TruthSegment(val maneuver: Maneuver, val start: Int, val end: Int)

class SyntheticDrive(val seed: Long, val samples: List<DrivingSample>, val labels: Array<Maneuver>, val truth: List<TruthSegment>)

/**
 * Randomized drives with a known label for every sample, for training and evaluating the maneuver classifier.
 *
 * Each maneuver is designed as the motion it should produce (a lateral acceleration profile for lane changes,
 * wobbles and curves; a yaw rate profile for turns) and converted to a steering wheel angle through the same
 * bicycle model the app uses, so the label is exact by construction. Parameters are drawn from wide ranges:
 * lane changes from 2 s to 6.5 s with lopsided halves and pauses, curves from 0.5 to 3.2 m/s² with lane
 * changes on some of them, turns and U-turns at 5 to 30 km/h, and in-lane wobbles that look like small lane
 * changes but move the car less than a meter. Speed ramps between maneuvers stay gentle.
 *
 * Out-and-back maneuvers (added 2026-11-13): a lane change straight into one back the other way. Moving 0.2 to
 * 0.8 m out stays inside the lane ([Maneuver.NONE]); 1.0 to 2.3 m is drifting over the lane line and back
 * ([Maneuver.LANE_DEPARTURE]); 2.8 to 3.9 m is a lane change and a lane change back.
 */
object SyntheticDrives {

    private const val DT = 0.1
    private const val DT_MS = 100L

    private enum class Kind { LANE_CHANGE, WEAVE, CURVE, TURN, U_TURN, WOBBLE, STRAIGHT, OUT_AND_BACK }

    private val kindWeights = listOf(
        Kind.LANE_CHANGE to 0.24, Kind.WEAVE to 0.06, Kind.CURVE to 0.18, Kind.TURN to 0.18,
        Kind.U_TURN to 0.07, Kind.WOBBLE to 0.17, Kind.STRAIGHT to 0.10, Kind.OUT_AND_BACK to 0.10,
    )

    fun generate(seed: Long, durationS: Double = 200.0, cfg: AnalyzerConfig = AnalyzerConfig()): SyntheticDrive {
        val rnd = Random(seed)
        val n = (durationS / DT).toInt()
        val speed = DoubleArray(n)
        val lateral = DoubleArray(n) // m/s², used where yawOverride is NaN
        val yawOverride = DoubleArray(n) { Double.NaN } // rad/s, for turns
        val labels = Array(n) { Maneuver.NONE }
        val signal = Array(n) { Turn.OFF }
        val truth = mutableListOf<TruthSegment>()

        fun u(a: Double, b: Double) = a + (b - a) * rnd.nextDouble()
        fun sign() = if (rnd.nextBoolean()) -1 else 1

        var p = 0
        var v = u(15.0, 80.0)
        while (p < n) {
            val kind = pick(rnd)
            val target = when (kind) {
                Kind.LANE_CHANGE, Kind.WEAVE, Kind.OUT_AND_BACK -> u(35.0, 110.0)
                Kind.CURVE -> u(40.0, 110.0)
                Kind.TURN -> u(8.0, 30.0)
                Kind.U_TURN -> u(5.0, 16.0)
                Kind.WOBBLE, Kind.STRAIGHT -> u(15.0, 110.0)
            }
            // Straight lead-in, long enough to change speed gently
            val rate = u(1.5, 3.5)
            val lead = (abs(target - v) / rate + u(1.5, 6.0)).let { (it / DT).toInt() }
            val body = buildManeuver(kind, target, rnd, cfg)
            if (p + lead + body.length + 20 > n) {
                for (i in p until n) speed[i] = v
                break
            }
            for (i in 0 until lead) {
                val t = i * DT
                speed[p + i] = if (target > v) min(target, v + rate * t) else max(target, v - rate * t)
            }
            p += lead
            v = target
            val wiggle = u(0.0, 1.5)
            val wigglePeriod = u(4.0, 12.0)
            for (i in 0 until body.length) {
                speed[p + i] = v + wiggle * sin(2 * PI * i * DT / wigglePeriod)
                lateral[p + i] = body.lateral[i]
                yawOverride[p + i] = body.yaw[i]
                labels[p + i] = body.labels[i]
            }
            body.segments.forEach { truth += TruthSegment(it.maneuver, p + it.start, p + it.end) }
            // Signal most lane changes and turns, a few seconds ahead
            body.segments.filter { it.maneuver != Maneuver.NONE && rnd.nextDouble() < 0.6 }.forEach { seg ->
                val dir = when (seg.maneuver) {
                    Maneuver.LANE_CHANGE_LEFT, Maneuver.TURN_LEFT, Maneuver.U_TURN, Maneuver.CURVE_LEFT -> Turn.LEFT
                    // Nobody signals drifting over the line
                    Maneuver.LANE_DEPARTURE -> return@forEach
                    else -> Turn.RIGHT
                }
                val from = (p + seg.start - (u(1.0, 3.0) / DT).toInt()).coerceAtLeast(0)
                for (i in from until p + seg.end) signal[i] = dir
            }
            p += body.length
        }

        val noise = Random(seed * 31 + 7)
        val samples = (0 until n).map { i ->
            val vKmh = speed[i].coerceAtLeast(0.0)
            val vMs = (vKmh / 3.6).coerceAtLeast(0.5)
            val yaw = if (!yawOverride[i].isNaN()) yawOverride[i] else lateral[i] / vMs
            val roadWheel = atan(yaw * cfg.wheelbaseM / vMs)
            val steering = Math.toDegrees(roadWheel) * cfg.steeringRatio + noise.nextGaussian() * 0.3
            DrivingSample(
                timeMs = i * DT_MS,
                speedKmh = (vKmh + noise.nextGaussian() * 0.2).coerceAtLeast(0.0).toFloat(),
                steeringDeg = steering.coerceIn(-540.0, 540.0).toFloat(),
                turnSignal = signal[i],
                speedLimitKmh = 80f,
            )
        }
        return SyntheticDrive(seed, samples, labels, truth)
    }

    private fun pick(rnd: Random): Kind {
        var x = rnd.nextDouble() * kindWeights.sumOf { it.second }
        for ((k, w) in kindWeights) {
            x -= w
            if (x <= 0) return k
        }
        return Kind.STRAIGHT
    }

    private class Body(val length: Int) {
        val lateral = DoubleArray(length)
        val yaw = DoubleArray(length) { Double.NaN }
        val labels = Array(length) { Maneuver.NONE }
        val segments = mutableListOf<TruthSegment>()

        fun label(m: Maneuver, start: Int, end: Int) {
            for (i in start until end) labels[i] = m
            segments.removeAll { it.start >= start && it.end <= end && it.maneuver != m }
            segments += TruthSegment(m, start, end)
        }
    }

    /**
     * Lateral acceleration of one lane change: two half-sine lobes of opposite sign whose areas cancel, so the
     * car ends up parallel to where it started, scaled to move it [offsetM] sideways (capped at [maxAcc]).
     */
    private fun laneChangeProfile(totalS: Double, ratio: Double, pauseS: Double, offsetM: Double, maxAcc: Double, sign: Int): DoubleArray {
        val t1 = totalS * ratio / (1 + ratio)
        val t2 = totalS - t1
        val n1 = (t1 / DT).toInt().coerceAtLeast(2)
        val np = (pauseS / DT).toInt()
        val n2 = (t2 / DT).toInt().coerceAtLeast(2)
        val shape = DoubleArray(n1 + np + n2)
        for (i in 0 until n1) shape[i] = sin(PI * (i + 0.5) / n1)
        for (i in 0 until n2) shape[n1 + np + i] = -(n1.toDouble() / n2) * sin(PI * (i + 0.5) / n2)
        // Offset of the unit shape, by integrating twice
        var vLat = 0.0
        var y = 0.0
        for (a in shape) {
            vLat += a * DT
            y += vLat * DT
        }
        var scale = offsetM / abs(y)
        val peak = shape.maxOf { abs(it) }
        if (scale * peak > maxAcc) scale = maxAcc / peak
        return DoubleArray(shape.size) { shape[it] * scale * sign }
    }

    private fun buildManeuver(kind: Kind, speedKmh: Double, rnd: Random, cfg: AnalyzerConfig): Body {
        fun u(a: Double, b: Double) = a + (b - a) * rnd.nextDouble()
        val sign = if (rnd.nextBoolean()) -1 else 1
        fun lcLabel(s: Int) = if (s < 0) Maneuver.LANE_CHANGE_LEFT else Maneuver.LANE_CHANGE_RIGHT
        fun randomLaneChange(s: Int) = laneChangeProfile(
            totalS = u(2.0, 6.5), ratio = u(0.75, 1.33),
            pauseS = if (rnd.nextDouble() < 0.3) u(0.0, 0.5) else 0.0,
            offsetM = u(2.8, 3.9), maxAcc = u(3.5, 6.5), sign = s,
        )
        val tail = (u(1.0, 3.0) / DT).toInt()

        return when (kind) {
            Kind.LANE_CHANGE -> {
                val lc = randomLaneChange(sign)
                Body(lc.size + tail).apply {
                    lc.copyInto(lateral)
                    label(lcLabel(sign), 0, lc.size)
                }
            }
            Kind.WEAVE -> {
                val a = randomLaneChange(sign)
                val gap = (u(0.0, 0.3) / DT).toInt()
                val b = randomLaneChange(sign)
                Body(a.size + gap + b.size + tail).apply {
                    a.copyInto(lateral)
                    b.copyInto(lateral, a.size + gap)
                    label(lcLabel(sign), 0, a.size)
                    label(lcLabel(sign), a.size + gap, a.size + gap + b.size)
                }
            }
            Kind.CURVE -> {
                val level = u(0.5, 3.2) * sign
                val rampIn = (u(1.0, 4.0) / DT).toInt()
                val hold = (u(3.0, 22.0) / DT).toInt()
                val rampOut = (u(1.0, 4.0) / DT).toInt()
                val len = rampIn + hold + rampOut
                val drift = u(0.0, 0.1)
                val driftPeriod = u(3.0, 8.0)
                Body(len + tail).apply {
                    for (i in 0 until len) {
                        val f = when {
                            i < rampIn -> (i + 1).toDouble() / rampIn
                            i < rampIn + hold -> 1.0 + drift * sin(2 * PI * (i - rampIn) * DT / driftPeriod)
                            else -> (len - i).toDouble() / rampOut
                        }
                        lateral[i] = level * f
                    }
                    label(if (sign < 0) Maneuver.CURVE_LEFT else Maneuver.CURVE_RIGHT, 0, len)
                    // A lane change on some bends, toward either side
                    if (rnd.nextDouble() < 0.4) {
                        val s = if (rnd.nextBoolean()) -1 else 1
                        val lc = randomLaneChange(s)
                        if (hold > lc.size + 20) {
                            val at = rampIn + 10 + rnd.nextInt(hold - lc.size - 20 + 1)
                            for (i in lc.indices) lateral[at + i] += lc[i]
                            label(lcLabel(s), at, at + lc.size)
                        }
                    }
                }
            }
            Kind.TURN, Kind.U_TURN -> {
                val headingRad = Math.toRadians(if (kind == Kind.TURN) u(65.0, 115.0) else u(160.0, 200.0))
                val vMs = speedKmh / 3.6
                val maxByGrip = u(2.5, 5.5) / vMs
                val maxByLock = vMs * tan(Math.toRadians(540.0 / cfg.steeringRatio)) / cfg.wheelbaseM
                var peak = min(Math.toRadians(u(15.0, 60.0)), min(maxByGrip, maxByLock * 0.95))
                val rampIn = u(0.6, 1.5)
                val rampOut = u(0.6, 1.5)
                var holdS = headingRad / peak - (rampIn + rampOut) / 2
                if (holdS < 0) {
                    holdS = 0.0
                    peak = headingRad / ((rampIn + rampOut) / 2)
                }
                val nIn = (rampIn / DT).toInt()
                val nHold = (holdS / DT).toInt()
                val nOut = (rampOut / DT).toInt()
                val len = nIn + nHold + nOut
                val m = when {
                    kind == Kind.U_TURN -> Maneuver.U_TURN
                    sign < 0 -> Maneuver.TURN_LEFT
                    else -> Maneuver.TURN_RIGHT
                }
                Body(len + tail).apply {
                    for (i in 0 until len) {
                        val f = when {
                            i < nIn -> (i + 1).toDouble() / nIn
                            i < nIn + nHold -> 1.0
                            else -> (len - i).toDouble() / nOut
                        }
                        lateral[i] = 0.0
                        yaw[i] = peak * f * sign
                    }
                    label(m, 0, len)
                }
            }
            Kind.WOBBLE -> {
                // A small S that moves the car well under a meter, or a single one-sided correction
                val shape = if (rnd.nextBoolean()) {
                    laneChangeProfile(u(1.0, 3.5), u(0.75, 1.33), 0.0, u(0.2, 0.9), 6.0, sign)
                } else {
                    val len = (u(0.5, 2.0) / DT).toInt().coerceAtLeast(2)
                    val amp = u(0.2, 1.0) * sign
                    DoubleArray(len) { amp * sin(PI * (it + 0.5) / len) }
                }
                Body(shape.size + tail).apply { shape.copyInto(lateral) }
            }
            Kind.STRAIGHT -> Body((u(3.0, 10.0) / DT).toInt())
            Kind.OUT_AND_BACK -> {
                val r = rnd.nextDouble()
                val weave = r < 0.25
                val departure = !weave && r < 0.7
                val offset = when {
                    weave -> u(0.2, 0.8)
                    departure -> u(1.0, 2.3)
                    else -> u(2.8, 3.9)
                }
                val total = if (weave || departure) u(1.6, 4.0) else u(2.0, 5.0)
                fun half(s: Int) = laneChangeProfile(total, u(0.8, 1.25), 0.0, offset, u(3.5, 6.5), s)
                val out = half(sign)
                val gap = (u(0.0, 0.3) / DT).toInt()
                val back = half(-sign)
                Body(out.size + gap + back.size + tail).apply {
                    out.copyInto(lateral)
                    back.copyInto(lateral, out.size + gap)
                    if (weave) {
                        // Inside the lane: nothing to report
                    } else if (departure) {
                        label(Maneuver.LANE_DEPARTURE, 0, out.size + gap + back.size)
                    } else {
                        label(lcLabel(sign), 0, out.size)
                        label(lcLabel(-sign), out.size + gap, out.size + gap + back.size)
                    }
                }
            }
        }
    }
}
