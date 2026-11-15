package com.iclab.steerscore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo's central claim, as a 2×2 design: the speed variable moves only the baseline, the steering
 * variable moves only the steering deductions, and SteerScore sees both.
 */
class ScenarioComparisonTest {

    private val samples = Scenarios.matrix.associateWith { it.build() }
    private val analyzed = samples.mapValues { (_, s) -> TripAnalyzer().apply { s.forEach { onSample(it) } } }

    private val nn = analyzed.getValue(Scenarios.NORMAL)
    private val ns = analyzed.getValue(Scenarios.STEER_ONLY)
    private val sn = analyzed.getValue(Scenarios.SPEED_ONLY)
    private val ss = analyzed.getValue(Scenarios.BOTH)

    @Test
    fun scenariosShareEverythingButTheirOwnVariable() {
        val n = samples.getValue(Scenarios.NORMAL)
        val sizes = samples.values.map { it.size }.toSet()
        assertEquals(1, sizes.size)
        // Same speed variant → identical speed; same steering variant → identical steering and turn signal
        assertSameSpeed(n, samples.getValue(Scenarios.STEER_ONLY))
        assertSameSpeed(samples.getValue(Scenarios.SPEED_ONLY), samples.getValue(Scenarios.BOTH))
        assertSameSteering(n, samples.getValue(Scenarios.SPEED_ONLY))
        assertSameSteering(samples.getValue(Scenarios.STEER_ONLY), samples.getValue(Scenarios.BOTH))
        samples.values.flatten().forEach { assertEquals(Scenarios.SPEED_LIMIT_KMH, it.speedLimitKmh) }
    }

    @Test
    fun speedVariantsAgreeDuringEveryLaneChange() {
        val normal = samples.getValue(Scenarios.STEER_ONLY)
        val abnormal = samples.getValue(Scenarios.BOTH)
        for (lc in ns.allEvents.filterIsInstance<LaneChange>() + nn.allEvents.filterIsInstance<LaneChange>()) {
            val window = (lc.timeMs - 2000)..(lc.timeMs + lc.durationMs + 1000)
            normal.indices.filter { normal[it].timeMs in window }.forEach { i ->
                assertEquals("t=${normal[i].timeMs}", normal[i].speedKmh, abnormal[i].speedKmh, 0f)
            }
        }
    }

    @Test
    fun baselineDependsOnlyOnSpeed() {
        for (rules in SpeedRuleSets.all) {
            assertEquals(rules.id, 100, nn.baselineScore(rules.id))
            assertEquals(rules.id, nn.baselineScore(rules.id), ns.baselineScore(rules.id))
            assertEquals(rules.id, sn.baselineScore(rules.id), ss.baselineScore(rules.id))
            assertTrue(rules.id, sn.baselineScore(rules.id) <= 75)
        }
    }

    @Test
    fun steeringDeductionDependsOnlyOnSteering() {
        assertEquals(0f, nn.steeringPenalty, 1e-6f)
        assertEquals(nn.steeringPenalty, sn.steeringPenalty, 1e-6f)
        assertEquals(ns.steeringPenalty, ss.steeringPenalty, 1e-6f)
        assertTrue(ns.steeringPenalty >= 30f)
        // Same lane changes, judged the same way, whichever speed variant drove them
        assertEquals(nn.allEvents.filterIsInstance<LaneChange>(), sn.allEvents.filterIsInstance<LaneChange>())
        assertEquals(ns.allEvents.filterIsInstance<LaneChange>(), ss.allEvents.filterIsInstance<LaneChange>())
    }

    @Test
    fun normalSteeringIsCleanAndAbnormalSteeringIsCaught() {
        val clean = nn.allEvents.filterIsInstance<LaneChange>()
        assertEquals(5, clean.size)
        clean.forEach {
            assertTrue(it.signaled)
            assertFalse(it.abrupt)
        }
        val risky = ns.allEvents.filterIsInstance<LaneChange>()
        // Two single lane changes, two from the weave, one signaled but abrupt
        assertEquals(5, risky.size)
        assertTrue(risky.all { it.abrupt && it.peakLateral >= AnalyzerConfig().abruptLateral + 0.2f })
        assertEquals(1, risky.count { it.signaled })
        assertTrue(analyzed.values.none { a -> a.allEvents.any { it is Cornering && it.hard } })
    }

    @Test
    fun abnormalSpeedHasTheScriptedEventsUnderEveryRuleSet() {
        for (rules in SpeedRuleSets.all) {
            val speed = sn.events(rules.id).filterNot { it.isSteering }
            assertEquals(rules.id, 4, speed.count { it is HardAcceleration })
            assertEquals(rules.id, 3, speed.count { it is HardBraking })
            assertEquals(rules.id, 2, speed.count { it is Speeding })
        }
    }

    @Test
    fun steerScoreSeparatesAllFour() {
        for (rules in SpeedRuleSets.all) {
            val id = rules.id
            assertEquals(id, 100, nn.steerScore(id))
            assertTrue(id, ns.steerScore(id) < nn.steerScore(id))
            assertTrue(id, sn.steerScore(id) < nn.steerScore(id))
            assertTrue(id, ss.steerScore(id) < minOf(ns.steerScore(id), sn.steerScore(id)))
            // The baseline cannot tell ① from ②, nor ③ from ④; SteerScore can
            assertTrue(id, ns.steerScore(id) != nn.steerScore(id) && ss.steerScore(id) != sn.steerScore(id))
        }
    }

    @Test
    fun enforcementZonesAreReported() {
        fun results(s: Scenario) = EnforcementMonitor(s.zones).apply { samples.getValue(s).forEach { onSample(it) } }.results
        val normal = results(Scenarios.NORMAL)
        assertEquals(2, normal.size)
        assertTrue(normal.none { it.violated })

        val (camera, section) = results(Scenarios.SPEED_ONLY)
        // Braking for the camera keeps it clear, but the average over the section is still too high
        assertEquals(ZoneKind.CAMERA, camera.zone.kind)
        assertFalse(camera.violated)
        assertEquals(ZoneKind.SECTION, section.zone.kind)
        assertTrue(section.violated)
    }

    @Test
    fun documentedScoresHold() {
        // The numbers in the README (baseline to SteerScore)
        val tmap = SpeedRuleSets.TMAP.id
        val kakao = SpeedRuleSets.KAKAO.id
        assertEquals(listOf(100, 100, 100, 68, 69, 69, 69, 36),
            listOf(nn, ns, sn, ss).flatMap { listOf(it.baselineScore(tmap), it.steerScore(tmap)) })
        assertEquals(listOf(100, 100, 100, 68, 71, 71, 71, 38),
            listOf(nn, ns, sn, ss).flatMap { listOf(it.baselineScore(kakao), it.steerScore(kakao)) })
    }

    private fun assertSameSpeed(a: List<DrivingSample>, b: List<DrivingSample>) =
        a.zip(b).forEach { (x, y) -> assertEquals(x.speedKmh, y.speedKmh, 0f) }

    private fun assertSameSteering(a: List<DrivingSample>, b: List<DrivingSample>) =
        a.zip(b).forEach { (x, y) ->
            assertEquals(x.steeringDeg, y.steeringDeg, 0f)
            assertEquals(x.turnSignal, y.turnSignal)
        }
}
