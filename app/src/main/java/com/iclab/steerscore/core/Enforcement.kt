package com.iclab.steerscore.core

enum class ZoneKind { CAMERA, SECTION }

/** A fixed speed camera (a point in time on a scripted route) or an average-speed section. */
data class EnforcementZone(
    val kind: ZoneKind,
    val label: String,
    val startMs: Long,
    val endMs: Long = startMs,
)

/** Speed at a camera, or average speed over a section, against the posted limit. */
data class EnforcementResult(val zone: EnforcementZone, val speedKmh: Float, val limitKmh: Float?) {
    val violated: Boolean get() = limitKmh != null && speedKmh > limitKmh
}

/**
 * Reports how the car passed each enforcement zone. Informational only: like the existing services,
 * neither score treats a camera differently from the rest of the road.
 */
class EnforcementMonitor(zones: List<EnforcementZone>) {

    private val pending = zones.sortedBy { it.startMs }.toMutableList()
    private val _results = mutableListOf<EnforcementResult>()
    val results: List<EnforcementResult> get() = _results

    /** The zone the car is approaching or inside right now, for the on-screen hint. */
    var current: EnforcementZone? = null
        private set

    private var prev: DrivingSample? = null
    private var sectionSpeedMs = 0.0
    private var sectionMs = 0L

    fun onSample(s: DrivingSample): EnforcementResult? {
        val last = prev
        prev = s
        val zone = pending.firstOrNull()
        if (zone == null) {
            current = null
            return null
        }
        val t = s.timeMs
        val enteredAt = if (zone.kind == ZoneKind.CAMERA) zone.startMs - CAMERA_APPROACH_MS else zone.startMs
        current = zone.takeIf { t >= enteredAt && t <= zone.endMs }

        return when (zone.kind) {
            ZoneKind.CAMERA -> if (t >= zone.startMs) finish(zone, s.speedKmh, s) else null
            ZoneKind.SECTION -> {
                if (last != null && t > zone.startMs) {
                    // Time-weighted speed over the part of this step inside the section = average speed
                    val from = maxOf(last.timeMs, zone.startMs)
                    val to = minOf(t, zone.endMs)
                    if (to > from) {
                        sectionSpeedMs += s.speedKmh.toDouble() * (to - from)
                        sectionMs += to - from
                    }
                }
                if (t >= zone.endMs && sectionMs > 0) finish(zone, (sectionSpeedMs / sectionMs).toFloat(), s) else null
            }
        }
    }

    private fun finish(zone: EnforcementZone, speedKmh: Float, s: DrivingSample): EnforcementResult {
        pending.removeAt(0)
        sectionSpeedMs = 0.0
        sectionMs = 0L
        return EnforcementResult(zone, speedKmh, s.speedLimitKmh).also { _results += it }
    }

    companion object {
        /** A camera is announced this long before it is reached, like a navigation warning. */
        const val CAMERA_APPROACH_MS = 3000L
    }
}
