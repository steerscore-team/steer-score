package com.iclab.steerscore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LateralDetectorTest {

    private val cfg = AnalyzerConfig()

    /** Straight driving, then one sine period of steering starting at 2 s, then straight again. */
    private fun laneChange(
        speedKmh: Float,
        amplitudeDeg: Double,
        periodS: Double,
        signal: Turn = Turn.OFF,
    ): List<DrivingSample> = (0..120).map { i ->
        val t = i / 10.0
        val x = (t - 2.0) / periodS
        val steer = if (x in 0.0..1.0) amplitudeDeg * sin(2 * PI * x) else 0.0
        val signalOn = signal != Turn.OFF && t in 1.0..(2.0 + periodS)
        DrivingSample(i * 100L, speedKmh, steer.toFloat(), if (signalOn) signal else Turn.OFF)
    }

    private fun run(samples: List<DrivingSample>): List<DrivingEvent> {
        val d = LateralDetector(cfg)
        return samples.flatMap { d.onSample(it) }
    }

    private fun List<DrivingEvent>.laneChanges() = filterIsInstance<LaneChange>()

    @Test
    fun lateralAccelerationIsSignedAndGrowsWithSpeed() {
        assertTrue(lateralAcceleration(80f, -10f, cfg) < 0f)
        assertTrue(lateralAcceleration(80f, 10f, cfg) > 0f)
        assertTrue(lateralAcceleration(100f, 10f, cfg) > lateralAcceleration(50f, 10f, cfg))
        assertEquals(0f, lateralAcceleration(80f, 0f, cfg), 1e-6f)
    }

    @Test
    fun smoothSignaledLaneChangeHasNoPenalty() {
        val events = run(laneChange(85f, -5.0, 5.0, Turn.LEFT))
        assertEquals(1, events.size)
        val lc = events[0] as LaneChange
        assertEquals(Turn.LEFT, lc.direction)
        assertTrue(lc.signaled)
        assertFalse(lc.abrupt)
        assertEquals(0f, lc.penalty, 1e-6f)
    }

    @Test
    fun abruptUnsignaledLaneChangeIsPenalizedTwice() {
        val events = run(laneChange(90f, 14.0, 2.4))
        val lc = events.single() as LaneChange
        assertEquals(Turn.RIGHT, lc.direction)
        assertTrue(lc.abrupt)
        assertFalse(lc.signaled)
        assertEquals(cfg.penalties.abruptLaneChange + cfg.penalties.unsignaledLaneChange, lc.penalty, 1e-4f)
    }

    @Test
    fun signalInTheWrongDirectionDoesNotCount() {
        val lc = run(laneChange(85f, -5.0, 5.0, Turn.RIGHT)).single() as LaneChange
        assertEquals(Turn.LEFT, lc.direction)
        assertFalse(lc.signaled)
    }

    @Test
    fun sameSteeringIsHarmlessAtLowSpeed() {
        assertTrue(run(laneChange(20f, 14.0, 2.4)).isEmpty())
    }

    @Test
    fun sustainedCurveIsNotALaneChange() {
        val samples = (0..150).map { i ->
            val t = i / 10.0
            val steer = if (t in 1.0..11.0) -8f else 0f
            DrivingSample(i * 100L, 80f, steer)
        }
        // Logged as a curve, with no penalty, and the heading change of the bend
        val curve = run(samples).single() as Cornering
        assertEquals(Turn.LEFT, curve.direction)
        assertFalse(curve.hard)
        assertEquals(0f, curve.penalty, 1e-6f)
        assertTrue(curve.headingChangeDeg in -60f..-25f)
    }

    @Test
    fun curveTakenTooFastIsHardCornering() {
        val samples = (0..150).map { i ->
            val t = i / 10.0
            val steer = if (t in 1.0..11.0) -25f else 0f
            DrivingSample(i * 100L, 80f, steer)
        }
        val curve = run(samples).single() as Cornering
        assertTrue(curve.hard)
        assertEquals(cfg.penalties.hardCornering, curve.penalty, 1e-6f)
    }

    /** 80 km/h on a long left-hand bend (-6°), with one sine-shaped lane change starting at [lcStartS]. */
    private fun laneChangeOnCurve(lcStartS: Double, periodS: Double, amplitudeDeg: Double, signal: Turn = Turn.OFF) =
        (0..300).map { i ->
            val t = i / 10.0
            val bend = when {
                t < 2.0 -> 0.0
                t < 4.0 -> -6.0 * (t - 2.0) / 2.0
                t < 24.0 -> -6.0
                t < 26.0 -> -6.0 * (26.0 - t) / 2.0
                else -> 0.0
            }
            val x = (t - lcStartS) / periodS
            val lc = if (x in 0.0..1.0) amplitudeDeg * sin(2 * PI * x) else 0.0
            val signalOn = signal != Turn.OFF && t in (lcStartS - 1.5)..(lcStartS + periodS)
            DrivingSample(i * 100L, 80f, (bend + lc).toFloat(), if (signalOn) signal else Turn.OFF)
        }

    @Test
    fun gentleLaneChangeTowardTheInsideOfACurveIsDetected() {
        val events = run(laneChangeOnCurve(10.0, 5.0, -6.0, Turn.LEFT))
        val lc = events.laneChanges().single()
        assertEquals(Turn.LEFT, lc.direction)
        assertTrue(lc.onCurve)
        assertTrue(lc.signaled)
        assertFalse(lc.abrupt)
        assertTrue(lc.lateralOffsetM in 2f..6f)
        // The bend itself is still one curve, not split by the lane change
        assertEquals(1, events.count { it is Cornering })
    }

    @Test
    fun gentleLaneChangeTowardTheOutsideOfACurveIsDetected() {
        val lc = run(laneChangeOnCurve(10.0, 5.0, 6.0, Turn.RIGHT)).laneChanges().single()
        assertEquals(Turn.RIGHT, lc.direction)
        assertTrue(lc.onCurve)
        assertFalse(lc.abrupt)
    }

    @Test
    fun abruptLaneChangeOnACurveIsDetected() {
        val lc = run(laneChangeOnCurve(10.0, 2.2, 20.0)).laneChanges().single()
        assertEquals(Turn.RIGHT, lc.direction)
        assertTrue(lc.abrupt)
        assertFalse(lc.signaled)
    }

    @Test
    fun quickWobbleInsideTheLaneIsNotALaneChange() {
        // A short twitch left and right that moves the car well under a lane width sideways
        val samples = (0..80).map { i ->
            val t = i / 10.0
            val x = (t - 2.0) / 1.5
            val steer = if (x in 0.0..1.0) 6.0 * sin(2 * PI * x) else 0.0
            DrivingSample(i * 100L, 80f, steer.toFloat())
        }
        assertTrue(run(samples).laneChanges().isEmpty())
    }

    @Test
    fun laneChangeRightAfterACurveIsDetected() {
        val samples = (0..150).map { i ->
            val t = i / 10.0
            val x = (t - 7.0) / 3.0
            val steer = when {
                t in 1.0..7.0 -> -8.0
                x in 0.0..1.0 -> 10.0 * sin(2 * PI * x)
                else -> 0.0
            }
            DrivingSample(i * 100L, 80f, steer.toFloat())
        }
        val events = run(samples)
        val lc = events.laneChanges().single()
        assertEquals(Turn.RIGHT, lc.direction)
        assertEquals(1, events.count { it is Cornering })
    }

    @Test
    fun hugeSteeringIsCappedAtTireGrip() {
        assertEquals(cfg.maxLateral, lateralAcceleration(80f, 150f, cfg), 1e-4f)
        assertEquals(-cfg.maxLateral, lateralAcceleration(80f, -150f, cfg), 1e-4f)

        // Virtual Console style: the wheel jumps to full lock one way, then the other, then back to center
        val samples = (0..80).map { i ->
            val t = i / 10.0
            val steer = when {
                t in 1.0..2.5 -> 150f
                t in 2.6..4.0 -> -150f
                else -> 0f
            }
            DrivingSample(i * 100L, 60f, steer)
        }
        val lc = run(samples).single() as LaneChange
        assertTrue(lc.abrupt)
        assertTrue(lc.peakLateral <= cfg.maxLateral)
    }

    @Test
    fun weavingWithoutReturningToCenterCountsEveryLaneChange() {
        // Right, left, right, left, then straight: two back-to-back lane changes to the right
        val samples = (0..100).map { i ->
            val t = i / 10.0
            val steer = when {
                t < 1.0 -> 0f
                t < 2.2 -> 60f
                t < 3.4 -> -60f
                t < 4.6 -> 60f
                t < 5.8 -> -60f
                else -> 0f
            }
            DrivingSample(i * 100L, 80f, steer)
        }
        val events = run(samples)
        assertEquals(2, events.size)
        events.forEach {
            val lc = it as LaneChange
            assertEquals(Turn.RIGHT, lc.direction)
            assertTrue(lc.abrupt)
        }
    }

    @Test
    fun nightMultipliesThePenalty() {
        val night = Conditions(lowBeam = true)
        val samples = laneChange(90f, 14.0, 2.4).map { it.copy(conditions = night) }
        val lc = run(samples).single()
        assertEquals(Conditions.NIGHT_STEERING_MULTIPLIER, lc.multiplier, 1e-6f)
        assertTrue(lc.penalty > lc.basePenalty)
    }
}
