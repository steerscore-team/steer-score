package com.iclab.steerscore.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double) {

    /** The point [eastM] metres east and [northM] metres north of this one (flat-earth, fine for a city). */
    fun offset(eastM: Double, northM: Double) = GeoPoint(
        lat + northM / METERS_PER_DEG_LAT,
        lon + eastM / (METERS_PER_DEG_LAT * cos(lat * PI / 180)),
    )

    fun distanceM(o: GeoPoint): Double {
        val north = (o.lat - lat) * METERS_PER_DEG_LAT
        val east = (o.lon - lon) * METERS_PER_DEG_LAT * cos((lat + o.lat) / 2 * PI / 180)
        return sqrt(north * north + east * east)
    }

    /** Compass bearing to [o] in degrees, 0 = north, 90 = east. */
    fun bearingTo(o: GeoPoint): Double {
        val north = (o.lat - lat) * METERS_PER_DEG_LAT
        val east = (o.lon - lon) * METERS_PER_DEG_LAT * cos((lat + o.lat) / 2 * PI / 180)
        return (atan2(east, north) * 180 / PI + 360) % 360
    }

    companion object {
        const val METERS_PER_DEG_LAT = 111_320.0
    }
}

/** Where a drive starts and which way the car faces (compass degrees), for dead reckoning without GPS. */
data class GeoStart(val point: GeoPoint, val headingDeg: Double)

/**
 * Position along a drive. With location fixes in the samples it follows them; without, it dead-reckons from the
 * vehicle speed and the yaw rate the steering implies (the same bicycle model the detectors use), starting at
 * [start]. Dead reckoning drifts over long drives, so it is only meant for scripted routes and location gaps.
 *
 * A fix that stays put while the speed says the car has covered [FROZEN_FIX_M] is treated as a gap too (a frozen
 * position in a tunnel, or the emulator's fixed location), until the fix moves again.
 */
class PositionTracker(start: GeoStart) {

    var position: GeoPoint = start.point
        private set
    /** Compass heading in degrees. */
    var headingDeg: Double = start.headingDeg
        private set
    private var lastMs: Long? = null
    private var lastFix: GeoPoint? = null
    /** Where the fix last moved, and how far the car has driven since by its speed. */
    private var fixAnchor: GeoPoint? = null
    private var drivenSinceFixMovedM = 0.0
    /** The last position came from a live location fix (not dead reckoning). */
    var onFix = false
        private set

    fun onSample(timeMs: Long, speedKmh: Float, yawRateDegS: Float, fix: GeoPoint?) {
        val prev = lastMs
        lastMs = timeMs
        val dt = if (prev == null) 0.0 else (timeMs - prev).coerceIn(0L, 1000L) / 1000.0
        if (fix != null) {
            val anchor = fixAnchor
            if (anchor == null || anchor.distanceM(fix) > FIX_MOVED_M) {
                fixAnchor = fix
                drivenSinceFixMovedM = 0.0
            } else {
                drivenSinceFixMovedM += speedKmh / 3.6 * dt
            }
            if (drivenSinceFixMovedM < FROZEN_FIX_M) {
                // Take the heading from movement between fixes, so dead reckoning continues the right way in a gap
                lastFix?.let { if (it.distanceM(fix) > 3.0) headingDeg = it.bearingTo(fix) }
                lastFix = fix
                position = fix
                onFix = true
                return
            }
        }
        onFix = false
        if (prev == null) return
        // Steering left is a negative yaw rate in the detectors; compass headings grow clockwise (to the right)
        headingDeg = (headingDeg + yawRateDegS * dt + 360) % 360
        val d = speedKmh / 3.6 * dt
        val h = headingDeg * PI / 180
        position = position.offset(d * sin(h), d * cos(h))
    }

    companion object {
        /** A fix closer than this to the last moving one counts as not moved (GPS jitter is a few metres). */
        const val FIX_MOVED_M = 5.0
        /** Driving this far by speed without the fix moving means the fix is frozen (about 4 s at 100 km/h). */
        const val FROZEN_FIX_M = 100.0
    }
}
