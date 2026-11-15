package com.iclab.steerscore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Scenario ⑥: every kind of steering maneuver in one drive, each classified as what it is. */
class ManeuverScenarioTest {

    private val a = TripAnalyzer().apply { Scenarios.MANEUVERS.build().forEach { onSample(it) } }
    private val steering = a.allEvents.filter { it.isSteering }.sortedBy { it.timeMs }

    @Test
    fun everyManeuverIsClassifiedOnce() {
        val kinds = steering.map {
            when (it) {
                is IntersectionTurn -> if (it.uTurn) "U" else "T${it.direction.name.first()}"
                is LaneChange -> (if (it.onCurve) "C" else "") + "L${it.direction.name.first()}"
                is Cornering -> "curve"
                else -> "?"
            }
        }
        // Right turn, lane change, curve, two lane changes on the curve, left turn, U-turn
        assertEquals(listOf("TR", "LL", "curve", "CLL", "CLR", "TL", "U"), kinds)
    }

    @Test
    fun turnsAndCurveHaveTheirHeadingChange() {
        val (right, left, uTurn) = steering.filterIsInstance<IntersectionTurn>()
        assertTrue(right.headingChangeDeg in 75f..105f)
        assertTrue(left.headingChangeDeg in -105f..-75f)
        assertTrue(abs(uTurn.headingChangeDeg) in 160f..200f)
        val curve = steering.filterIsInstance<Cornering>().single()
        assertFalse(curve.hard)
        // Turns far more than an intersection does, but at highway speed: a curve
        assertTrue(abs(curve.headingChangeDeg) > 60f)
    }

    @Test
    fun onlyTheRiskyManeuversCost() {
        val (inside, outside) = steering.filterIsInstance<LaneChange>().filter { it.onCurve }
        assertEquals(0f, inside.penalty, 1e-6f)
        assertTrue(outside.abrupt && !outside.signaled)
        val left = steering.filterIsInstance<IntersectionTurn>()[1]
        assertFalse(left.signaled)
        assertEquals(
            AnalyzerConfig().penalties.run { abruptLaneChange + unsignaledLaneChange + unsignaledTurn },
            a.steeringPenalty, 1e-4f,
        )
    }

    @Test
    fun noSpeedRuleFires() {
        assertTrue(a.allEvents.none { !it.isSteering })
        for (rules in SpeedRuleSets.all) assertEquals(100, a.baselineScore(rules.id))
    }
}
