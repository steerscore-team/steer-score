package com.iclab.steerscore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Going over and straight back (right, left-left, right on the wheel) must not read as a single lane change,
 * and never as a turn. Found with a Virtual Console drive on 2026-11-13: drifting about 1.2 m out and back was
 * scored as one unsignaled lane change and the way back was missed.
 */
class LaneDepartureTest {

    /** 60 km/h; at [startS] steer right [amp]° for [seg] s, left for 2·[seg] s, right for [seg] s. */
    private fun outAndBack(amp: Double, seg: Double, startS: Double = 5.0, kmh: Float = 60f): List<DrivingSample> =
        (0..250).map { i ->
            val t = i / 10.0
            val x = t - startS
            val deg = if (x in 0.0..4 * seg) amp * sin(2 * PI * x / (2 * seg)) * (if (x < 2 * seg) 1 else -1) else 0.0
            DrivingSample(timeMs = i * 100L, speedKmh = kmh, steeringDeg = deg.toFloat())
        }

    private fun steeringEvents(samples: List<DrivingSample>) =
        TripAnalyzer().apply { samples.forEach { onSample(it) } }.allEvents.filter { it.isSteering }

    @Test
    fun driftOverTheLineAndBackIsALaneDeparture() {
        val events = steeringEvents(outAndBack(amp = 20.0, seg = 1.0))
        val d = events.single() as LaneDeparture
        assertEquals(Turn.RIGHT, d.direction)
        assertTrue("peak ${d.peakOffsetM}", d.peakOffsetM in AnalyzerConfig().minDepartureOffsetM..AnalyzerConfig().laneReturnOffsetM)
        assertEquals(2f, d.penalty, 0f)
    }

    @Test
    fun fullLaneOutAndBackIsTwoLaneChanges() {
        val events = steeringEvents(outAndBack(amp = 40.0, seg = 1.1))
        assertEquals(events.toString(), 2, events.size)
        val (out, back) = events.map { it as LaneChange }
        assertEquals(Turn.RIGHT, out.direction)
        assertEquals(Turn.LEFT, back.direction)
        assertTrue(back.timeMs > out.timeMs)
        assertTrue("excursion ${out.lateralOffsetM}", out.lateralOffsetM >= AnalyzerConfig().laneReturnOffsetM)
    }

    @Test
    fun smallWeaveInsideTheLaneIsNothing() {
        assertTrue(steeringEvents(outAndBack(amp = 8.0, seg = 0.8)).isEmpty())
    }

    @Test
    fun weavingThroughTrafficStillCountsEveryLaneChange() {
        // ② keeps its two back-to-back lane changes to the left (L, R, L, R)
        val a = TripAnalyzer().apply { Scenarios.STEER_ONLY.build().forEach { onSample(it) } }
        assertEquals(5, a.allEvents.count { it is LaneChange })
        assertTrue(a.allEvents.none { it is LaneDeparture })
    }

    /**
     * The Virtual Console drives that found the problems, recorded by the app in live mode. The second run's
     * 10 Hz samples skipped 0.2 s at the start of the first steer, which once made the excursion integration
     * drift to the other side (2.2 m instead of about 1.2 m) and split the drift into two lane changes.
     */
    @Test
    fun virtualConsoleSwerveDrive() {
        for (name in listOf("/vc_swerve.csv", "/vc_swerve2.csv")) checkSwerveDrive(name)
    }

    private fun checkSwerveDrive(name: String) {
        val samples = javaClass.getResourceAsStream(name)!!.bufferedReader().useLines { SampleCsv.parse(it) }
        val events = steeringEvents(samples)
        // Small weave → nothing, about 1 m out and back → lane departure, quick jink at 20 km/h → not a turn
        assertEquals("$name $events", 1, events.size)
        assertTrue(name, events.single() is LaneDeparture)
        assertTrue(events.none { it is IntersectionTurn })
    }
}
