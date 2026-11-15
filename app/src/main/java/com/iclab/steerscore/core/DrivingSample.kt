package com.iclab.steerscore.core

enum class Turn { OFF, LEFT, RIGHT }

/**
 * Driving conditions from vehicle state. Only night (low beam on) is used, as TMAP also weights night events.
 * Rain and fog are not: the wiper state cannot tell rain from a washer wipe, and the SDK has no weather data.
 */
data class Conditions(
    val lowBeam: Boolean = false,
) {
    /** How much riskier the same steering maneuver is under these conditions; multiplies its penalty. */
    fun riskMultiplier(): Float = if (lowBeam) NIGHT_STEERING_MULTIPLIER else 1f

    val isNormal: Boolean get() = !lowBeam

    companion object {
        const val NIGHT_STEERING_MULTIPLIER = 1.2f
    }
}

/**
 * One snapshot of the vehicle, sampled at a fixed rate (10 Hz) by the live source or the replay player.
 * [steeringDeg] is the steering wheel angle with left turns negative, matching AAOS PERF_STEERING_ANGLE.
 */
data class DrivingSample(
    val timeMs: Long,
    val speedKmh: Float,
    val steeringDeg: Float,
    val turnSignal: Turn = Turn.OFF,
    val brakePressed: Boolean = false,
    val conditions: Conditions = Conditions(),
    val speedLimitKmh: Float? = null,
    /** Road class and situation from the navigation, when it guides a route. */
    val road: RoadInfo? = null,
    /** GPS fix when one is available (live drives with location permission); scenarios dead-reckon instead. */
    val location: GeoPoint? = null,
)
