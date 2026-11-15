package com.iclab.steerscore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripAnalyzerTest {

    private fun speedRamp(from: Float, to: Float, seconds: Int): List<DrivingSample> =
        (0..seconds * 10).map { i -> DrivingSample(i * 100L, from + (to - from) * i / (seconds * 10f), 0f) }

    private fun analyze(samples: List<DrivingSample>) = TripAnalyzer().apply { samples.forEach { onSample(it) } }

    @Test
    fun hardAccelerationAndBrakingUnderEveryRuleSet() {
        val accel = analyze(speedRamp(40f, 80f, 3))
        val brake = analyze(speedRamp(80f, 40f, 3))
        val gentle = analyze(speedRamp(0f, 80f, 14))
        for (rules in SpeedRuleSets.all) {
            assertTrue(rules.id, accel.events(rules.id).single() is HardAcceleration)
            assertTrue(rules.id, brake.events(rules.id).single() is HardBraking)
            assertTrue(rules.id, gentle.events(rules.id).isEmpty())
        }
    }

    @Test
    fun oneSecondRuleCatchesShortBurstsThatThreeSecondRuleMisses() {
        // +12 km/h within one second, then steady: hard for 1 s / 10 km/h, harmless for 3 s / 30 km/h
        val samples = (0..50).map { i ->
            val v = if (i < 10) 60f else if (i < 20) 60f + (i - 10) * 1.2f else 72f
            DrivingSample(i * 100L, v, 0f)
        }
        val a = analyze(samples)
        assertEquals(1, a.events(SpeedRuleSets.TMAP.id).size)
        assertTrue(a.events(SpeedRuleSets.TMAP_3S.id).isEmpty())
    }

    @Test
    fun speedingNeedsSustainedExcess() {
        val short = analyze((0..40).map { DrivingSample(it * 100L, 120f, 0f, speedLimitKmh = 100f) })
        assertTrue(short.events(SpeedRuleSets.TMAP.id).isEmpty())

        val long = analyze((0..80).map { DrivingSample(it * 100L, 120f, 0f, speedLimitKmh = 100f) })
        assertTrue(long.events(SpeedRuleSets.TMAP.id).single() is Speeding)
    }

    @Test
    fun nightWeighsBaselineEvents() {
        val night = Conditions(lowBeam = true)
        val a = analyze(speedRamp(80f, 40f, 3).map { it.copy(conditions = night) })
        val e = a.events(SpeedRuleSets.TMAP.id).single()
        assertEquals(SpeedRuleSets.TMAP.nightMultiplier, e.multiplier, 1e-6f)
    }

    @Test
    fun steerScoreAddsSteeringDeductionsToTheBaseline() {
        val a = analyze(Scenarios.BOTH.build())
        for (rules in SpeedRuleSets.all) {
            val expected = 100f - a.speedPenalty(rules.id) - a.steeringPenalty
            assertEquals(Math.round(expected).coerceIn(0, 100), a.steerScore(rules.id))
            assertTrue(a.steerScore(rules.id) < a.baselineScore(rules.id))
        }
    }

    @Test
    fun distanceIntegratesSpeed() {
        val a = analyze((0..600).map { DrivingSample(it * 100L, 60f, 0f) })
        assertEquals(1.0, a.distanceKm, 0.01)
    }

    @Test
    fun mixedScenarioProducesTheScriptedEvents() {
        val a = analyze(Scenarios.MIXED.build())
        val laneChanges = a.allEvents.filterIsInstance<LaneChange>()
        assertEquals(4, laneChanges.size)

        val (smooth, abrupt, nightAbrupt, nightUnsignaled) = laneChanges
        assertEquals(Turn.LEFT, smooth.direction)
        assertTrue(smooth.signaled)
        assertFalse(smooth.abrupt)

        assertEquals(Turn.RIGHT, abrupt.direction)
        assertTrue(abrupt.abrupt)
        assertFalse(abrupt.signaled)

        assertEquals(Turn.LEFT, nightAbrupt.direction)
        assertTrue(nightAbrupt.abrupt)
        assertTrue(nightAbrupt.signaled)
        assertTrue(nightAbrupt.multiplier > 1f)

        assertEquals(Turn.RIGHT, nightUnsignaled.direction)
        assertFalse(nightUnsignaled.abrupt)
        assertFalse(nightUnsignaled.signaled)

        // The long curve must not be reported at all
        assertNull(a.allEvents.firstOrNull { it is Cornering && it.hard })
        assertEquals(1, a.allEvents.count { it is Cornering })
        for (rules in SpeedRuleSets.all) {
            val speed = a.events(rules.id).filterNot { it.isSteering }
            assertEquals(rules.id, 1, speed.count { it is HardBraking })
            assertEquals(rules.id, 1, speed.count { it is HardAcceleration })
            assertEquals(rules.id, 1, speed.count { it is Speeding })
        }
    }

    @Test
    fun csvRoundTrip() {
        val samples = Scenarios.MIXED.build().take(50)
        val text = sequenceOf(SampleCsv.HEADER) + samples.map { SampleCsv.format(it) }.asSequence()
        val parsed = SampleCsv.parse(text)
        assertEquals(samples.size, parsed.size)
        assertEquals(samples[10].timeMs, parsed[10].timeMs)
        assertEquals(samples[10].speedKmh, parsed[10].speedKmh, 0.01f)
        assertEquals(samples[10].speedLimitKmh, parsed[10].speedLimitKmh)
    }
}
