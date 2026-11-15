package com.iclab.steerscore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMapTest {

    @Test
    fun everyDemoPairHasAMapWithEachRoute() {
        val demo = RouteAdvisor.loadDemo()
        for (od in demo.ods) {
            val m = assertNotNull("map for ${od.id}", RouteMaps.of(od.id)).let { RouteMaps.of(od.id)!! }
            assertEquals(od.id, od.routes.indices.toSet(), m.routes.keys)
            assertTrue(od.id, m.roads.size > 20)
            m.routes.values.forEach { pts ->
                assertTrue(od.id, pts.size >= 2)
                // Each route starts and ends near the pair's start and destination
                assertTrue(od.id, pts.first().distanceM(m.from) < 1500)
                assertTrue(od.id, pts.last().distanceM(m.to) < 1500)
            }
        }
    }

    @Test
    fun liveDemoHasTheNavigationsThreeRoutes() {
        // Guiding to 중앙대학교광명병원 (the emulator demo destination): the navigation's three routes with shapes
        val demo = assertNotNull(RouteMaps.liveDemoFor(GeoPoint(37.4235, 126.8857))).let { RouteMaps.liveDemoFor(GeoPoint(37.4235, 126.8857))!! }
        // Every route the navigation offered while testing (its choices change with the time of day)
        assertEquals(listOf("RECOMMENDED", "안양판교로", "수도권제1순환고속도로", "강남순환로"), demo.od.routes.map { it.options.single() })
        // Distance, time and toll as the navigation's route screen showed them
        assertEquals(listOf(27.0, 28.0, 32.0, 33.0), demo.od.routes.map { it.distanceKm })
        assertEquals(listOf(1330, 1680, 700, 3800), demo.od.routes.map { it.tollWon })
        assertEquals(setOf(0, 1, 2, 3), demo.map.routes.keys)
        // No waypoints (one on the wrong side of the road made the navigation loop); place names recognise the route
        assertTrue(demo.vias.isEmpty())
        assertEquals(2, demo.guidedIndex(listOf("판교IC 방면", "평촌IC 방면")))
        assertEquals(0, demo.guidedIndex(listOf("판교IC 방면", "석수IC - 광명 방면")))
        // Every destination of the example history has the navigation's routes too, each alternative with a waypoint
        for (to in listOf(GeoPoint(37.3947, 127.1112), GeoPoint(37.4841, 127.0346), GeoPoint(37.4979, 127.0276),
            GeoPoint(37.2512, 127.0714), GeoPoint(37.4490, 126.4510))) {
            val d = RouteMaps.liveDemoFor(to)
            assertTrue("$to", d != null && d.od.routes.size == 3 && d.map.routes.size == 3 && d.turnTexts.keys == setOf(0, 1, 2))
            // The navigation's own turn texts of a route identify it
            d!!.turnTexts.forEach { (i, texts) -> assertEquals("$to $i", i, d.guidedIndex(texts)) }
        }
        // A destination without bundled routes
        assertEquals(null, RouteMaps.liveDemoFor(GeoPoint(35.1796, 129.0756)))
        // Different routes, different predictions for the same driver
        val scores = RouteAdvisor.loadDemo().let { d -> d.advisor(d.personas.first()).predictAll(demo.od.routes).map { it.score } }
        assertTrue(scores.toString(), scores.toSet().size >= 3)
    }

    @Test
    fun anyMetroDestinationGetsARoadBackground() {
        // Guiding to a destination without demo routes (판교 → 강남역): the metro roads around both ends, no route lines
        val m = assertNotNull(RouteMaps.around(GeoPoint(37.4127, 127.0947), GeoPoint(37.4979, 127.0276)))
            .let { RouteMaps.around(GeoPoint(37.4127, 127.0947), GeoPoint(37.4979, 127.0276))!! }
        assertTrue(m.routes.isEmpty())
        assertTrue(m.roads.size.toString(), m.roads.size > 100)
        assertTrue(m.roads.any { it.first == "motorway" })
        // Far outside the bundled area (부산): no map
        assertEquals(null, RouteMaps.around(GeoPoint(35.1796, 129.0756), GeoPoint(35.1587, 129.1604)))
    }

    @Test
    fun parsesTheMapFormat() {
        val m = RouteMaps.parse(
            listOf(
                "# comment",
                "P|x|37.40000,127.10000|37.50000,127.00000",
                "R|x|0|37.40000,127.10000 37.45000,127.05000 37.50000,127.00000",
                "B|x|motorway|37.41000,127.09000 37.42000,127.08000",
                "W|x|37.52000,126.98000 37.52000,127.02000",
            ),
        ).getValue("x")
        assertEquals(GeoPoint(37.4, 127.1), m.from)
        assertEquals(3, m.routes.getValue(0).size)
        assertEquals("motorway", m.roads.single().first)
        assertEquals(1, m.rivers.size)
    }
}
