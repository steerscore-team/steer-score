package com.iclab.steerscore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAdvisorTest {

    private val demo = RouteAdvisor.loadDemo()
    private fun persona(id: String) = demo.personas.first { it.id == id }
    private fun od(id: String) = demo.ods.first { it.id == id }
    private fun best(p: String, o: String) = demo.advisor(persona(p)).predictAll(od(o).routes).maxBy { it.score }

    @Test
    fun demoHasThreePersonasWithVariedDrivesOnTwelvePairs() {
        assertEquals(3, demo.personas.size)
        assertEquals(12, demo.ods.size)
        demo.personas.forEach { p ->
            assertTrue(p.trips.size >= 10)
            assertTrue(p.trips.map { it.od }.distinct().size >= 8)
            // Long expressway drives, short ones with some expressway, and city-only ones
            assertTrue(p.trips.any { it.route.expresswayKm > 50 } || p.id == "B")
            assertTrue(p.trips.any { it.route.expresswayKm in 1.0..30.0 })
            assertTrue(p.trips.any { it.route.expresswayKm == 0.0 })
            // Each trip's score is 100 minus its deductions, as in the app
            p.trips.forEach { t -> assertEquals(maxOf(0, Math.round(100 - t.events.sumOf { it.penalty.toDouble() }).toInt()), t.score) }
        }
        // Every pair offers at least 2 distinct routes, and every RouteOption is covered once per pair
        demo.ods.forEach { o ->
            assertTrue(o.routes.size >= 2)
            assertEquals(RouteAdvisor.OPTION_LABELS.keys, o.routes.flatMap { it.options }.toSet())
            assertEquals(7, o.routes.sumOf { it.options.size })
        }
    }

    @Test
    fun theBestRouteDependsOnTheDriver() {
        // A loses points merging and changing lanes on the expressway: the city route is predicted better for A,
        // while B, who loses points at intersections, is better off on the expressway
        assertEquals("SHORT_DISTANCE", best("A", "pangyo-yangjae").route.options.first())
        assertTrue(best("B", "pangyo-yangjae").route.expresswayKm > 0)
        assertTrue(best("B", "suji-gangnam").route.expresswayKm > 10)
        assertTrue(best("A", "seongnam-jamsil").route.expresswayKm == 0.0)
        // The steady driver C loses little anywhere: all candidates within a few points
        val c = demo.advisor(persona("C")).predictAll(od("pangyo-yangjae").routes).map { it.score }
        assertTrue(c.toString(), c.max() - c.min() <= 3)
    }

    @Test
    fun predictionExplainsItselfAndShrinksSparseHistory() {
        val a = demo.advisor(persona("A"))
        val p = a.predict(od("pangyo-yangjae").routes.first())
        assertEquals(100 - p.expectedPenalty, p.score.toDouble(), 0.5)
        assertTrue(p.top.isNotEmpty())
        // Without history the rate is the population's; one clean drive through 7 intersection turns pulls it
        // 7/10 of the way to zero (prior weight 3 turns)
        val popRate = RouteAdvisor(emptyList(), demo.allTrips).rate(Exposure.INTERSECTION, Cause.UNSIGNALED_TURN)
        assertTrue(popRate > 0.0)
        val clean = PastTrip("d", "x", "SHORT_DISTANCE", od("jeongja-seohyeon").routes[1], 100, emptyList())
        assertEquals(7, clean.route.counts[Situation.INTERSECTION])
        assertEquals(popRate * 3 / 10, RouteAdvisor(listOf(clean), demo.allTrips).rate(Exposure.INTERSECTION, Cause.UNSIGNALED_TURN), 1e-9)
    }

    @Test
    fun printTable() {
        demo.personas.forEach { p ->
            val a = demo.advisor(p)
            demo.ods.forEach { o ->
                println("${p.id} ${o.id}: " + a.predictAll(o.routes).joinToString("  ") { r ->
                    "${r.route.options.first()}=${r.score}(${r.route.timeMin}분) [" + r.top.joinToString { "${it.exposure.label}/${it.cause.label} %.1f".format(it.points) } + "]"
                })
            }
        }
    }
}
