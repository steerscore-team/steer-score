package com.iclab.steerscore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The after-trip report must add up to the scores on screen and name the right habits. */
class TripReportTest {

    private fun analyze(s: Scenario) = TripAnalyzer().apply { s.build().forEach { onSample(it) } }

    private val normal = analyze(Scenarios.NORMAL)
    private val both = analyze(Scenarios.BOTH)
    private val tmap = SpeedRuleSets.TMAP.id

    @Test
    fun timelineEndsAtTheScoresOnScreen() {
        for (s in Scenarios.all) {
            val a = analyze(s)
            for (rules in SpeedRuleSets.all) {
                val r = TripReport.of(a, rules.id)
                val last = r.steps.last()
                assertEquals("${s.title} ${rules.id}", a.baselineScore(rules.id), last.baseline)
                assertEquals("${s.title} ${rules.id}", a.steerScore(rules.id), last.steer)
                assertEquals(ScoreStep(0, 100, 100), r.steps.first())
                assertTrue(r.steps.zipWithNext().all { (p, q) -> q.offsetMs >= p.offsetMs && q.steer <= p.steer })
            }
        }
    }

    @Test
    fun causesAndTipsAccountForEveryPoint() {
        for (s in Scenarios.all) {
            val a = analyze(s)
            val r = TripReport.of(a, tmap)
            val total = a.speedPenalty(tmap) + a.steeringPenalty
            assertEquals(s.title, total, r.causes.sumOf { it.penalty.toDouble() }.toFloat(), 1e-3f)
            assertEquals(s.title, total, r.tips.sumOf { it.gain.toDouble() }.toFloat(), 1e-3f)
            assertTrue(r.causes.zipWithNext().all { (p, q) -> p.penalty >= q.penalty })
        }
    }

    @Test
    fun cleanDriveHasNothingToFix() {
        val r = TripReport.of(normal, tmap)
        assertEquals(listOf(ScoreStep(0, 100, 100)), r.steps)
        assertTrue(r.causes.isEmpty())
        assertTrue(r.tips.isEmpty())
        // Two signaled lane changes in a row are not weaving
        assertTrue(r.weaving.isEmpty())
    }

    @Test
    fun riskyDriveNamesUnsignaledAbruptLaneChangesFirst() {
        val r = TripReport.of(both, tmap)
        assertEquals(69, r.baselineScore)
        assertEquals(36, r.steerScore)
        val top = r.topCauses
        val p = Penalties()
        val tmapRules = SpeedRuleSets.TMAP
        listOf(
            Triple(Cause.UNSIGNALED_ABRUPT_LANE, 4, 4 * (p.abruptLaneChange + p.unsignaledLaneChange)),
            Triple(Cause.HARD_BRAKE, 3, 3 * tmapRules.brakingPenalty),
            Triple(Cause.HARD_ACCEL, 4, 4 * tmapRules.accelerationPenalty),
        ).forEachIndexed { i, (cause, count, points) ->
            assertEquals(cause, top[i].cause)
            assertEquals(count, top[i].count)
            assertEquals(points, top[i].penalty, 1e-3f)
        }
    }

    @Test
    fun backToBackAbruptLaneChangesAreOneWeavingRun() {
        val r = TripReport.of(both, tmap)
        assertEquals(1, r.weaving.size)
        val w = r.weaving.single()
        assertEquals(2, w.count)
        assertEquals(2 * (Penalties().abruptLaneChange + Penalties().unsignaledLaneChange), w.penalty, 1e-3f)
        // Scripted at 92 s and 94 s
        assertTrue(w.startOffsetMs in 91_000L..93_000L)
    }

    @Test
    fun tipsSplitCombinedDeductions() {
        val tips = TripReport.of(both, tmap).tips.associate { it.habit to it.gain }
        // Five abrupt lane changes and four unsignaled ones, three hard brakes
        val p = Penalties()
        assertEquals(5 * p.abruptLaneChange, tips.getValue(Habit.SLOW_LANE_CHANGE), 1e-3f)
        assertEquals(4 * p.unsignaledLaneChange, tips.getValue(Habit.SIGNAL), 1e-3f)
        assertEquals(3 * SpeedRuleSets.TMAP.brakingPenalty, tips.getValue(Habit.EARLY_BRAKING), 1e-3f)
    }
}
