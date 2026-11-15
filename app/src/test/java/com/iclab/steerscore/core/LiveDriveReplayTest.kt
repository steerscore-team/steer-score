package com.iclab.steerscore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A drive recorded by the app in live mode on 2026-11-13, driven through the Pleos Virtual Console HTTP API
 * (a scripted Virtual Console drive). Unlike the scripted scenarios, these samples came through the real signal path:
 * Virtual Console → vehicle HAL → Pleos Vehicle SDK / CarPropertyManager → LiveVehicleSource.
 *
 * Script: 0 → 80 km/h, signaled lane change to the left at 20 s, wipers and low beam on at 34 s (only the low beam
 * still counts; the wiper column is ignored since 2026-11-14),
 * unsignaled abrupt lane change to the right at 40 s, slow to 20 km/h, signaled right turn at 70 s, stop.
 */
class LiveDriveReplayTest {

    private val samples = javaClass.getResourceAsStream("/vc_live_drive.csv")!!
        .bufferedReader().useLines { SampleCsv.parse(it) }
    private val a = TripAnalyzer().apply { samples.forEach { onSample(it) } }
    private val steering = a.allEvents.filter { it.isSteering }

    @Test
    fun liveSignalsArrived() {
        assertTrue(samples.any { it.turnSignal == Turn.LEFT })
        assertTrue(samples.any { it.turnSignal == Turn.RIGHT })
        assertTrue(samples.any { it.conditions.lowBeam })
        assertTrue(samples.maxOf { it.speedKmh } >= 79f)
        // The SDK reports left as positive; LiveVehicleSource flips it, so the left lane change steers negative first
        val firstSteer = samples.first { kotlin.math.abs(it.steeringDeg) > 3f }
        assertTrue("first steering ${firstSteer.steeringDeg}", firstSteer.steeringDeg < 0f)
    }

    @Test
    fun eachManeuverIsJudgedAsScripted() {
        assertEquals(3, steering.size)
        val (left, right) = steering.filterIsInstance<LaneChange>()
        assertEquals(Turn.LEFT, left.direction)
        assertTrue(left.signaled)
        assertEquals(0f, left.penalty, 0f)

        assertEquals(Turn.RIGHT, right.direction)
        assertTrue(!right.signaled && right.abrupt)
        // Night (low beam, ×1.2) was on
        assertEquals(1.2f, right.multiplier, 1e-3f)
        val p = Penalties()
        assertEquals((p.abruptLaneChange + p.unsignaledLaneChange) * 1.2f, right.penalty, 1e-3f)

        val turn = steering.filterIsInstance<IntersectionTurn>().single()
        assertEquals(Turn.RIGHT, turn.direction)
        assertTrue(turn.signaled)
        assertEquals(0f, turn.penalty, 0f)
    }

    @Test
    fun scoresMatchTheAppScreen() {
        // Gentle speed changes only, so the baseline stays perfect
        for (rules in SpeedRuleSets.all) assertEquals(100, a.baselineScore(rules.id))
        // 100 − (4.08 + 3) × 1.2 (night) = 91.5. The app showed 87 on 2026-11-13, when the wiper still counted
        // as rain (×1.68) and an abrupt lane change cost 5
        assertEquals(92, a.steerScore(SpeedRuleSets.TMAP.id))
    }
}
