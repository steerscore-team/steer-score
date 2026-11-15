package com.iclab.steerscore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The shipped classifier: same numbers as in Python, and sensible verdicts on the demo scenarios. */
class ManeuverModelTest {

    private val model = ManeuverModel.parse(File("src/main/assets/maneuver_model.txt").readText())

    private fun analyze(samples: List<DrivingSample>) =
        TripAnalyzer(model = model).apply { samples.forEach { onSample(it) }; maneuvers.flush() }

    @Test
    fun kotlinInferenceMatchesPython() {
        val lines = javaClass.getResource("/maneuver_parity.txt")!!.readText().lines().filter { it.isNotBlank() }
        val bySeed = lines.map { it.split(" ") }.groupBy { it[0].toLong() }
        for ((seed, rows) in bySeed) {
            val a = analyze(SyntheticDrives.generate(seed).samples)
            for (row in rows) {
                val step = row[1].toInt()
                val expected = row.drop(2).map { it.toFloat() }
                val actual = model.predict(a.maneuvers.featuresAt(step))
                expected.indices.forEach { assertEquals("seed $seed step $step class $it", expected[it], actual[it], 2e-3f) }
            }
        }
    }

    @Test
    fun classifierAgreesWithTheRulesOnTheManeuverScenario() {
        val a = analyze(Scenarios.MANEUVERS.build())
        val steering = a.allEvents.filter { it.isSteering }
        steering.forEach { e ->
            val v = a.aiVerdict(e)
            assertNotNull(v)
            assertTrue("$e judged ${v!!.maneuver}", v.agreesWith(e))
        }
    }

    @Test
    fun classifierAgreesWithTheRulesOnTheMatrixScenarios() {
        for (scenario in Scenarios.matrix) {
            val a = analyze(scenario.build())
            val (judged, agreed) = a.maneuvers.agreement(a.allEvents, a::spanOf)
            assertEquals(scenario.title, a.allEvents.count { it.isSteering }, judged)
            assertEquals(scenario.title, judged, agreed)
        }
    }

    @Test
    fun withoutAModelThereIsNoVerdictAndScoresAreUnchanged() {
        val samples = Scenarios.BOTH.build()
        val plain = TripAnalyzer().apply { samples.forEach { onSample(it) } }
        val withAi = analyze(samples)
        assertTrue(plain.allEvents.filter { it.isSteering }.all { plain.aiVerdict(it) == null })
        for (rules in SpeedRuleSets.all) assertEquals(plain.steerScore(rules.id), withAi.steerScore(rules.id))
    }
}
