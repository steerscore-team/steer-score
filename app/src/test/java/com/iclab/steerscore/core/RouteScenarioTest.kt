package com.iclab.steerscore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⑦ real-road scenario (OpenStreetMap geometry): the road shape alone must not
 * produce lane changes, and the scripted deductions must land where they were put, in all three drives.
 */
class RouteScenarioTest {

    private fun analyze(n: Int) = Scenarios.routeTrip(n).let { sc ->
        TripAnalyzer(start = sc.start).apply { sc.build().forEach { onSample(it) } }
    }

    @Test
    fun everySampleHasGpsAndTheRouteIsTheRealLength() {
        val samples = Scenarios.ROUTE.build()
        assertTrue(samples.all { it.location != null })
        val km = samples.zipWithNext { a, b -> a.location!!.distanceM(b.location!!) }.sum() / 1000
        assertEquals(11.5, km, 0.4)
    }

    @Test
    fun scriptedDeductionsOnly() {
        val tmap = SpeedRuleSets.TMAP.id
        val (risky, medium, good) = (1..3).map(::analyze)
        assertEquals(listOf(90, 71), listOf(risky.baselineScore(tmap), risky.steerScore(tmap)))
        assertEquals(listOf(96, 89), listOf(medium.baselineScore(tmap), medium.steerScore(tmap)))
        assertEquals(listOf(100, 100), listOf(good.baselineScore(tmap), good.steerScore(tmap)))
        for (a in listOf(risky, medium, good)) {
            // The signaled right turn out of the start street is a real intersection turn
            val turn = a.allEvents.filterIsInstance<IntersectionTurn>().single()
            assertTrue(turn.signaled && turn.direction == Turn.RIGHT)
        }
        // Lane changes only where the drives make them (3 / 2 / 2), none from the road shape
        assertEquals(listOf(3, 2, 2), listOf(risky, medium, good).map { it.allEvents.filterIsInstance<LaneChange>().size })
        assertEquals(2, risky.allEvents.count { it is LaneChange && it.abrupt && !it.signaled })
        assertTrue(good.allEvents.filterIsInstance<LaneChange>().all { it.penalty == 0f })
    }

    @Test
    fun navigationRoadInfoNamesWhereEachDeductionHappened() {
        val log = TripLog.of(analyze(1), "⑦", 0L)
        assertTrue(log.roadInfo)
        val byCause = log.eventsFor(SpeedRuleSets.TMAP.id).groupBy { it.cause }
        // Flooring it up the on-ramp, the abrupt lane changes on the expressway, braking late for the exit
        assertTrue(byCause.getValue(Cause.HARD_ACCEL).single().situation in setOf(Situation.IC_ENTRY, Situation.MERGE))
        byCause.getValue(Cause.UNSIGNALED_ABRUPT_LANE).forEach {
            assertEquals(Situation.STRAIGHT, it.situation)
            assertEquals(RoadClass.EXPRESSWAY, it.roadClass)
        }
        assertEquals(Situation.IC_EXIT, byCause.getValue(Cause.HARD_BRAKE).single().situation)
        val ins = Insights.of(listOf(log), SpeedRuleSets.TMAP.id)
        assertEquals(RoadClass.EXPRESSWAY, ins.roadClasses.first().type)
        assertEquals("고속도로", ins.hotspots.first { it.topCause == Cause.UNSIGNALED_ABRUPT_LANE }.place)
        // The made-up scenarios have no navigation, so only the steering situations appear
        val plain = TripLog.of(TripAnalyzer().apply { Scenarios.BOTH.build().forEach { onSample(it) } }, "④", 0L)
        assertTrue(!plain.roadInfo && plain.events.all { it.roadClass == null })
        assertEquals(emptyList<RoadClassStat>(), Insights.of(listOf(plain), SpeedRuleSets.TMAP.id).roadClasses)
    }

    @Test
    fun lanesChangedOnTheExpresswayDoNotCarryOffTheRoad() {
        // Off the expressway (ramps, city road) every drive must follow the same road line; the risky drive's lane
        // changes once left it 3.5 m to the side of the 1-lane off-ramp, i.e. off the road
        fun offExpressway(n: Int) = Scenarios.routeTrip(n).build()
            .filter { it.road?.roadClass != null && it.road?.roadClass != RoadClass.EXPRESSWAY }
            .mapNotNull { it.location }
        val good = offExpressway(3)
        val risky = offExpressway(1)
        val worst = risky.filterIndexed { i, _ -> i % 10 == 0 }.maxOf { p -> good.minOf { it.distanceM(p) } }
        assertTrue("risky drive is $worst m off the good drive's line", worst < 1.5)
    }
}
