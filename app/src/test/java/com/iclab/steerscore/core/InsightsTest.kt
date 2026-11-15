package com.iclab.steerscore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dead reckoning, the per-trip log, and the cumulative analysis across trips. */
class InsightsTest {

    private val tmap = SpeedRuleSets.TMAP.id

    private fun analyze(s: Scenario) =
        TripAnalyzer(start = s.start).apply { s.build().forEach { onSample(it) } }

    private fun log(s: Scenario, endedAt: Long) = TripLog.of(analyze(s), s.title, endedAt)

    @Test
    fun deadReckoningFollowsSpeedAndSteering() {
        val start = GeoStart(GeoPoint(37.4, 127.1), headingDeg = 0.0)
        val straight = PositionTracker(start)
        for (i in 0..600) straight.onSample(i * 100L, 60f, 0f, null)
        assertEquals(1000.0, start.point.distanceM(straight.position), 5.0)
        assertEquals(0.0, start.point.bearingTo(straight.position), 0.5)

        // ⑥ starts heading north (10°), and its right turn, left turn and U-turn leave it facing roughly south
        val maneuvers = analyze(Scenarios.MANEUVERS)
        val route = maneuvers.track.map { it.point }
        assertTrue(route.first().distanceM(route.last()) > 300.0)
        // The logged route keeps a point about every 3 s of the 150 s drive
        val logged = TripLog.of(maneuvers, "⑥", 0).route
        assertTrue("route has ${logged.size} points", logged.size in 45..60)
        // GPS fixes win over dead reckoning
        val gps = PositionTracker(start)
        gps.onSample(0, 50f, 0f, GeoPoint(37.5, 127.0))
        assertEquals(GeoPoint(37.5, 127.0), gps.position)
    }

    @Test
    fun frozenFixFallsBackToDeadReckoningUntilItMovesAgain() {
        val start = GeoStart(GeoPoint(37.4, 127.1), headingDeg = 0.0)
        val stuck = GeoPoint(37.3947, 127.1112)
        val t = PositionTracker(start)
        // Standing still on a fixed position: the fix is right, keep it
        for (i in 0..100) t.onSample(i * 100L, 0f, 0f, stuck)
        assertEquals(stuck, t.position)
        // 72 km/h for 60 s (1.2 km) while the fix never moves (emulator, tunnel): the route must not collapse
        for (i in 101..700) t.onSample(i * 100L, 72f, 0f, stuck)
        assertEquals(1200.0 - PositionTracker.FROZEN_FIX_M, stuck.distanceM(t.position), 25.0)
        // The fix moves again: follow it
        val moving = stuck.offset(0.0, 1500.0)
        t.onSample(70_100L, 72f, 0f, moving)
        assertEquals(moving, t.position)
        // A normal 1 Hz fix at 100 km/h moves 28 m each second, so it is never taken for frozen
        val live = PositionTracker(start)
        var p = start.point
        for (i in 0..600) {
            if (i % 10 == 0) p = p.offset(0.0, 27.8)
            live.onSample(i * 100L, 100f, 0f, p)
            assertEquals(p, live.position)
        }
    }

    @Test
    fun repeatedRouteLandsOnTheSameSpots() {
        val a = log(Scenarios.STEER_ONLY, 1)
        val b = log(Scenarios.STEER_ONLY, 2)
        assertEquals(a.events.map { it.point }, b.events.map { it.point })
        val insights = Insights.of(listOf(a, b), tmap)
        // Every hotspot holds the same deductions from both trips
        assertTrue(insights.hotspots.isNotEmpty())
        insights.hotspots.forEach { assertEquals(2, it.trips); assertEquals(0, it.count % 2) }
        // ② per trip: 5 abrupt lane changes + 4 unsignaled ones
        val p = Penalties()
        assertEquals(2 * (5 * p.abruptLaneChange + 4 * p.unsignaledLaneChange), insights.totalPenalty, 1e-3f)
    }

    @Test
    fun situationsComeFromTheSteering() {
        val m = log(Scenarios.MANEUVERS, 1)
        val turn = m.events.single { it.cause == Cause.UNSIGNALED_TURN }
        assertEquals(Situation.INTERSECTION, turn.situation)
        val curveLaneChange = m.events.single { it.cause == Cause.UNSIGNALED_ABRUPT_LANE }
        assertEquals(Situation.CURVE, curveLaneChange.situation)
        // ②'s lane changes happen on the straight part of the route
        log(Scenarios.STEER_ONLY, 1).events.forEach { assertEquals(Situation.STRAIGHT, it.situation) }
        // Logs saved before the straight/curve/intersection split read their old names as straight
        assertEquals(Situation.STRAIGHT, Situation.parse("HIGHWAY"))
        assertEquals(Situation.STRAIGHT, Situation.parse("URBAN"))
        assertEquals(Situation.CURVE, Situation.parse("CURVE"))
    }

    @Test
    fun everyPointIsAccountedFor() {
        // As if recorded from GPS, so every trip is on a map
        val logs = Scenarios.all.mapIndexed { i, s -> log(s, i.toLong()).copy(gps = true) }
        for (rules in SpeedRuleSets.all) {
            val insights = Insights.of(logs, rules.id)
            val expected = Scenarios.all.sumOf { s ->
                val a = analyze(s)
                (a.speedPenalty(rules.id) + a.steeringPenalty).toDouble()
            }.toFloat()
            assertEquals(rules.id, expected, insights.totalPenalty, 1e-2f)
            assertEquals(rules.id, expected, insights.habits.sumOf { it.gain.toDouble() }.toFloat(), 1e-2f)
            assertEquals(1f, insights.situations.sumOf { it.share.toDouble() }.toFloat(), 1e-3f)
            // Every deduction is on one of the maps
            assertEquals(expected, insights.areas.flatMap { it.hotspots }.sumOf { it.penalty.toDouble() }.toFloat(), 1e-2f)
            assertEquals(Scenarios.all.size, insights.tripCount)
        }
    }

    @Test
    fun improvementPlanPutsTheCostliestHabitFirst() {
        // Three risky drives, then three clean ones
        val logs = (0 until 3).map { log(Scenarios.BOTH, it.toLong()) } + (3 until 6).map { log(Scenarios.NORMAL, it.toLong()) }
        val insights = Insights.of(logs, tmap)
        val goal = assertNotNull(insights.goal).let { insights.goal!! }
        // ④ loses 5 abrupt lane changes (about 20.4 points) per trip, more than any other habit
        assertEquals(Habit.SLOW_LANE_CHANGE, goal.habit)
        val abrupt = Penalties().abruptLaneChange
        assertEquals(3 * 5 * abrupt, goal.gain, 1e-3f)
        assertEquals(3 * 5 * abrupt / 6, goal.perTrip, 1e-3f)
        assertEquals(Situation.STRAIGHT, goal.topSituation)
        val (before, after) = insights.recentChange()!!
        assertEquals(36f, before, 1e-3f)
        assertEquals(100f, after, 1e-3f)
    }

    @Test
    fun tripsInDifferentPlacesGetSeparateMaps() {
        // ①–⑥ as the "시나리오 6개 기록 추가" button logs them, then ② replayed: ①–④ share one road, ⑤ and ⑥ are elsewhere
        val logs = (Scenarios.all.mapIndexed { i, s -> log(s, i.toLong()) } + log(Scenarios.STEER_ONLY, 10)).map { it.copy(gps = true) }
        // Without GPS (virtual roads, dead reckoning) there is no real place: no map at all, the totals still count them
        val noGps = Insights.of(logs.map { it.copy(gps = false) }, tmap)
        assertTrue(noGps.areas.isEmpty())
        assertEquals(logs.size, noGps.tripCount)
        val areas = Insights.of(logs, tmap).areas
        assertEquals(3, areas.size)
        // The latest trip's map first, with all four 2×2 drives (② twice) on it
        assertEquals(5, areas[0].trips)
        assertEquals(Scenarios.STEER_ONLY.title, areas[0].labels.first())
        assertEquals(setOf(Scenarios.MIXED.title), areas[1].labels.toSet() + areas[2].labels.toSet() - Scenarios.MANEUVERS.title)
        // ⑦'s three drives are one map
        assertEquals(1, Insights.of(Scenarios.ROUTE_TRIPS.mapIndexed { i, s -> log(s, i.toLong()) }, tmap).areas.size)
    }

    @Test
    fun realRoadTripsNeverShareAMapWithVirtualOnes() {
        // ⑦ on real roads (drawn over the road map) and ② on its virtual road nearby: two maps, the real one keeps its background
        val real = Scenarios.ROUTE_TRIPS.mapIndexed { i, s -> log(s, i.toLong()).copy(gps = true) }
        val virtual = log(Scenarios.STEER_ONLY, 10).copy(gps = false)
        val areas = Insights.of(real + virtual, tmap).areas
        assertTrue(areas.none { a -> a.labels.contains(Scenarios.STEER_ONLY.title) && a.labels.contains(Scenarios.ROUTE_TRIPS[0].title) })
        assertTrue(areas.any { it.gps && it.trips == 3 })
    }

    @Test
    fun farAwayTripsStayOffTheMap() {
        val here = log(Scenarios.STEER_ONLY, 2)
        val far = log(Scenarios.STEER_ONLY, 1).let { t ->
            t.copy(route = t.route.map { it.offset(0.0, 100_000.0) }, events = t.events.map { it.copy(point = it.point.offset(0.0, 100_000.0)) })
        }
        val insights = Insights.of(listOf(far, here), tmap)
        assertEquals(1, insights.routes.size)
        insights.hotspots.forEach { assertEquals(1, it.trips) }
        // ...but still count in the totals
        assertEquals(2, insights.tripCount)
    }

    @Test
    fun csvKeepsLocation() {
        val s = DrivingSample(0, 50f, 0f, location = GeoPoint(37.123456, 127.654321))
        val back = SampleCsv.parse(sequenceOf(SampleCsv.format(s))).single()
        assertEquals(37.123456, back.location!!.lat, 1e-6)
        assertEquals(127.654321, back.location!!.lon, 1e-6)
        // Rows from before location was recorded still parse
        val old = SampleCsv.parse(sequenceOf("100,50.00,0.00,OFF,0,0,0,0,80.0")).single()
        assertEquals(null, old.location)
    }
}
