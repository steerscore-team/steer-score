package com.iclab.steerscore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnforcementMonitorTest {

    private fun drive(speedAt: (Double) -> Float) =
        (0..300).map { i -> DrivingSample(i * 100L, speedAt(i / 10.0), 0f, speedLimitKmh = 80f) }

    @Test
    fun cameraReadsTheSpeedAtThePoint() {
        val m = EnforcementMonitor(listOf(EnforcementZone(ZoneKind.CAMERA, "camera", 10_000)))
        drive { t -> if (t < 10.0) 100f else 70f }.forEach { m.onSample(it) }
        val r = m.results.single()
        assertEquals(70f, r.speedKmh, 0.01f)
        assertFalse(r.violated)
    }

    @Test
    fun sectionAveragesSpeedOverTime() {
        val m = EnforcementMonitor(listOf(EnforcementZone(ZoneKind.SECTION, "section", 10_000, 20_000)))
        // Half the section at 100, half at 60: average 80 is not over the limit
        drive { t -> if (t < 15.0) 100f else 60f }.forEach { m.onSample(it) }
        val r = m.results.single()
        assertEquals(80f, r.speedKmh, 0.5f)
        assertFalse(r.violated)

        val fast = EnforcementMonitor(listOf(EnforcementZone(ZoneKind.SECTION, "section", 10_000, 20_000)))
        drive { 90f }.forEach { fast.onSample(it) }
        assertTrue(fast.results.single().violated)
    }

    @Test
    fun currentZoneIsAnnouncedBeforeTheCameraAndClearedAfter() {
        val m = EnforcementMonitor(listOf(EnforcementZone(ZoneKind.CAMERA, "camera", 10_000)))
        val samples = drive { 60f }
        samples.take(60).forEach { m.onSample(it) }
        assertNull(m.current)
        samples.subList(60, 95).forEach { m.onSample(it) }
        assertEquals("camera", m.current?.label)
        samples.drop(95).forEach { m.onSample(it) }
        assertNull(m.current)
    }
}
