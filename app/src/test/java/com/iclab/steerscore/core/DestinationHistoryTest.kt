package com.iclab.steerscore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationHistoryTest {

    private val hospital = Place("중앙대학교광명병원", 37.42348, 126.88573, poiId = "1")
    private val office = Place("판교역", 37.3947, 127.1112)
    private val gym = Place("헬스장", 37.40, 127.10)
    private val hour = 3_600_000L

    @Test
    fun mostDrivenFirstAndPinnedOnTop() {
        val h = DestinationHistory()
        h.record(office, 0)
        h.record(office, 2 * hour)
        h.record(hospital, 3 * hour)
        h.record(office, 5 * hour)
        h.record(gym, 6 * hour)
        h.record(hospital, 8 * hour)
        // 판교역 3 drives, 병원 2, 헬스장 1 (most recent, still last)
        assertEquals(listOf("판교역", "중앙대학교광명병원", "헬스장"), h.sorted().map { it.name })
        assertEquals(listOf(3, 2, 1), h.sorted().map { it.count })
        // ★ pins 헬스장 to the top; ★ again unpins it
        h.togglePin(gym)
        assertEquals("헬스장", h.sorted().first().name)
        h.togglePin(gym)
        assertEquals("판교역", h.sorted().first().name)
    }

    @Test
    fun oneDriveIsCountedOnce() {
        val h = DestinationHistory()
        assertTrue(h.record(hospital, 0))
        // Opening the screen again, re-routing or switching routes within 30 minutes is the same drive
        assertFalse(h.record(hospital, 10 * 60_000L))
        // The same place by name nearby (no POI id) is the same destination
        assertFalse(h.record(Place("중앙대학교광명병원", 37.4236, 126.8858), 20 * 60_000L))
        assertTrue(h.record(hospital, 2 * hour))
        assertEquals(2, h.places.single().count)
    }

    @Test
    fun theNavigationsRecentDestinationsSeedTheList() {
        val h = DestinationHistory(listOf(hospital.copy(count = 4, lastAt = hour)))
        h.addRecent(listOf(hospital, office, gym), 10 * hour)
        // Already known: unchanged; new ones: one drive each, in the navigation's order
        assertEquals(4, h.places.first { it.name == "중앙대학교광명병원" }.count)
        assertEquals(listOf("중앙대학교광명병원", "판교역", "헬스장"), h.sorted().map { it.name })
    }
}
