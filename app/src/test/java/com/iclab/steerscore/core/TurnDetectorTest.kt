package com.iclab.steerscore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Turns, curves and lane changes run through the whole analyzer, so each is classified exactly once. */
class TurnDetectorTest {

    private val cfg = AnalyzerConfig()

    /** Steering trapezoid at [speedKmh]: 1 s in, [holdS] held at [deg], 1 s out, starting at 3 s. */
    private fun turn(speedKmh: Float, deg: Double, holdS: Double, signal: Turn = Turn.OFF) = (0..150).map { i ->
        val t = i / 10.0
        val x = t - 3.0
        val steer = when {
            x < 0 -> 0.0
            x < 1.0 -> deg * x
            x < 1.0 + holdS -> deg
            x < 2.0 + holdS -> deg * (2.0 + holdS - x)
            else -> 0.0
        }
        val signalOn = signal != Turn.OFF && t in 0.0..(3.0 + holdS + 2.0)
        DrivingSample(i * 100L, speedKmh, steer.toFloat(), if (signalOn) signal else Turn.OFF)
    }

    private fun analyze(samples: List<DrivingSample>) = TripAnalyzer(cfg).apply { samples.forEach { onSample(it) } }.allEvents

    @Test
    fun signaledRightTurnAtAnIntersection() {
        val e = analyze(turn(20f, 250.0, 1.7, Turn.RIGHT)).single() as IntersectionTurn
        assertEquals(Turn.RIGHT, e.direction)
        assertFalse(e.uTurn)
        assertTrue(e.headingChangeDeg in 75f..105f)
        assertTrue(e.signaled)
        assertEquals(0f, e.penalty, 1e-6f)
    }

    @Test
    fun unsignaledLeftTurnIsPenalized() {
        val e = analyze(turn(20f, -250.0, 1.7)).single() as IntersectionTurn
        assertEquals(Turn.LEFT, e.direction)
        assertTrue(e.headingChangeDeg in -105f..-75f)
        assertFalse(e.signaled)
        assertEquals(cfg.penalties.unsignaledTurn, e.penalty, 1e-6f)
    }

    @Test
    fun uTurnTurnsAboutHalfACircle() {
        val e = analyze(turn(15f, -450.0, 2.7, Turn.LEFT)).single() as IntersectionTurn
        assertTrue(e.uTurn)
        assertTrue(abs(e.headingChangeDeg) in 160f..200f)
    }

    @Test
    fun turningTooFastIsAHardTurn() {
        // Same wheel angle at 32 km/h: well over 4 m/s² sideways
        val e = analyze(turn(32f, 250.0, 0.5, Turn.RIGHT)).filterIsInstance<IntersectionTurn>().single()
        assertTrue(e.hard)
        assertEquals(cfg.penalties.hardCornering, e.penalty, 1e-6f)
    }

    @Test
    fun smallSteeringAtCitySpeedIsNotATurn() {
        // Following a gentle bend in town: turns well under 60°
        assertTrue(analyze(turn(25f, 40.0, 3.0)).none { it is IntersectionTurn })
    }

    @Test
    fun highwayBendIsACurveNotATurn() {
        // 80 km/h, held left for 20 s: turns more than 60° in total, but far too fast for an intersection
        val samples = (0..300).map { i ->
            val t = i / 10.0
            DrivingSample(i * 100L, 80f, if (t in 2.0..22.0) -8f else 0f)
        }
        val events = analyze(samples)
        val curve = events.single() as Cornering
        assertTrue(abs(curve.headingChangeDeg) >= cfg.turnMinHeadingDeg)
    }

    @Test
    fun laneChangeIsNotATurn() {
        // Abrupt lane change: the yaw rate is high, but the two halves cancel out
        val samples = (0..100).map { i ->
            val t = i / 10.0
            val x = (t - 2.0) / 2.2
            DrivingSample(i * 100L, 80f, if (x in 0.0..1.0) (20.0 * sin(2 * PI * x)).toFloat() else 0f)
        }
        val events = analyze(samples)
        assertTrue(events.single() is LaneChange)
    }
}
