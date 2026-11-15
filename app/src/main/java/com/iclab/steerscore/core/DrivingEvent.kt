package com.iclab.steerscore.core

sealed interface DrivingEvent {
    val timeMs: Long
    val basePenalty: Float
    /** Condition multiplier at the time of the event (night). */
    val multiplier: Float
    val conditions: Conditions
    val penalty: Float get() = basePenalty * multiplier

    /** The [SpeedRules] that produced a speed event; null for the steering events SteerScore adds. */
    val ruleId: String? get() = null
    val isSteering: Boolean get() = ruleId == null
}

/**
 * An S-shaped maneuver that ends roughly parallel to where it started, shifted sideways by about a lane.
 * [headingChangeDeg] and [lateralOffsetM] are estimated from steering and speed relative to the road
 * (the curve's own steering is subtracted when the lane change happens on a bend).
 */
data class LaneChange(
    override val timeMs: Long,
    val direction: Turn,
    val durationMs: Long,
    val peakLateral: Float,
    val signaled: Boolean,
    val abrupt: Boolean,
    override val basePenalty: Float,
    override val multiplier: Float,
    override val conditions: Conditions,
    val headingChangeDeg: Float = 0f,
    val lateralOffsetM: Float = 0f,
    /** True when the lane change happened while the road itself was bending. */
    val onCurve: Boolean = false,
) : DrivingEvent

/**
 * Drifting partly into the next lane and straight back (right, left, left, right on the wheel). The car ends in its
 * own lane, parallel, so it is not a lane change, but it left its lane: a sign of inattention or weaving.
 */
data class LaneDeparture(
    override val timeMs: Long,
    val direction: Turn,
    val durationMs: Long,
    /** Furthest the car got from its starting line, in metres. */
    val peakOffsetM: Float,
    val peakLateral: Float,
    override val basePenalty: Float,
    override val multiplier: Float,
    override val conditions: Conditions,
) : DrivingEvent

/** A bend in the road followed at speed. Logged with no penalty unless taken too hard. */
data class Cornering(
    override val timeMs: Long,
    val direction: Turn,
    val durationMs: Long,
    val headingChangeDeg: Float,
    val peakLateral: Float,
    val hard: Boolean,
    override val basePenalty: Float,
    override val multiplier: Float,
    override val conditions: Conditions,
) : DrivingEvent

/** A turn at an intersection (about 90°) or a U-turn (about 180°), at low speed. */
data class IntersectionTurn(
    override val timeMs: Long,
    val direction: Turn,
    val uTurn: Boolean,
    val durationMs: Long,
    val headingChangeDeg: Float,
    val peakLateral: Float,
    val signaled: Boolean,
    val hard: Boolean,
    override val basePenalty: Float,
    override val multiplier: Float,
    override val conditions: Conditions,
) : DrivingEvent

data class HardAcceleration(
    override val timeMs: Long,
    val deltaKmh: Float,
    val windowMs: Long,
    override val basePenalty: Float,
    override val multiplier: Float,
    override val conditions: Conditions,
    override val ruleId: String,
) : DrivingEvent

data class HardBraking(
    override val timeMs: Long,
    val deltaKmh: Float,
    val windowMs: Long,
    override val basePenalty: Float,
    override val multiplier: Float,
    override val conditions: Conditions,
    override val ruleId: String,
) : DrivingEvent

data class Speeding(
    override val timeMs: Long,
    val overKmh: Float,
    override val basePenalty: Float,
    override val multiplier: Float,
    override val conditions: Conditions,
    override val ruleId: String,
) : DrivingEvent
