package com.iclab.steerscore.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/** The 시연 모드 drives along the bundled navigation routes (generated offline). */
class LiveRouteDrivesTest {

    private fun drive(od: String, index: Int): List<DrivingSample> {
        val res = javaClass.getResourceAsStream("/scenarios/live/${od}_$index.csv.gz")
        assertNotNull("missing drive $od $index", res)
        return GZIPInputStream(res!!).bufferedReader(Charsets.UTF_8).useLines { SampleCsv.parse(it) }
    }

    @Test
    fun everyRouteHasADriveThatFollowsItsLine() {
        for (demo in RouteMaps.liveDemos) {
            for ((index, line) in demo.map.routes) {
                val samples = drive(demo.od.id, index)
                val fixes = samples.mapNotNull { it.location }
                assertTrue("${demo.od.id} $index has GPS", fixes.size == samples.size)
                // Starts where the navigation places the car and ends at the destination
                assertTrue(fixes.first().distanceM(line.first()) < 50)
                assertTrue("${demo.od.id} $index ends at the destination", fixes.last().distanceM(line.last()) < 150)
                // Never far from the route line (lane changes move it by a few metres)
                val dense = line.zipWithNext().flatMap { (p, q) ->
                    val n = maxOf(1, (p.distanceM(q) / 25).toInt())
                    (0 until n).map { k -> GeoPoint(p.lat + (q.lat - p.lat) * k / n, p.lon + (q.lon - p.lon) * k / n) }
                } + line.last()
                val off = fixes.filterIndexed { i, _ -> i % 50 == 0 }.maxOf { p -> dense.minOf { it.distanceM(p) } }
                assertTrue("${demo.od.id} $index strays ${off.toInt()} m", off < 120)
            }
        }
    }

    @Test
    fun theDriveHasHabitsToFix() {
        // 양재역 ③ 경부고속도로: the expressway stretch brings deductions, the score drops below 100
        val a = TripAnalyzer(AnalyzerConfig(), start = Scenarios.ROUTE.start)
        drive("live-yangjae", 2).forEach { a.onSample(it) }
        val rule = SpeedRuleSets.TMAP.id
        assertTrue("score ${a.steerScore(rule)}", a.steerScore(rule) in 50..95)
        assertTrue(a.track.any { it.road?.roadClass == RoadClass.EXPRESSWAY })
    }
}
