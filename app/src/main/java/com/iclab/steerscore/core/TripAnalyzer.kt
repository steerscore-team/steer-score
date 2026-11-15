package com.iclab.steerscore.core

import kotlin.math.roundToInt

/**
 * Scores one trip under every baseline rule set at once, so the baseline can be switched after the drive.
 *
 * - Baseline score: 100 minus the speed-only deductions of the chosen [SpeedRules].
 * - SteerScore: the same baseline deductions plus the steering deductions (identical for every rule set).
 */
class TripAnalyzer(
    private val cfg: AnalyzerConfig = AnalyzerConfig(),
    val ruleSets: List<SpeedRules> = SpeedRuleSets.all,
    /** Learned maneuver classifier. It only labels events for comparison; scores always come from the rules. */
    model: ManeuverModel? = null,
    /** Start point for dead reckoning when the samples carry no GPS fix. */
    start: GeoStart = Scenarios.DEFAULT_START,
) {

    private val lateral = LateralDetector(cfg)
    private val turns = TurnDetector(cfg)
    /** Per-sample yaw rate, lateral acceleration and speed, and the classifier's labels when a model is set. */
    val maneuvers = ManeuverTimeline(model)
    private val speedDetectors = ruleSets.map { LongitudinalDetector(it) to SpeedingDetector(it) }
    private val positions = PositionTracker(start)
    private val _track = mutableListOf<TrackPoint>()
    /** Where the car was about once a second, for placing events on the cumulative map. */
    val track: List<TrackPoint> get() = _track

    private val _events = mutableListOf<DrivingEvent>()
    /** Steering events once, plus speed events once per rule set. */
    val allEvents: List<DrivingEvent> get() = _events

    var startMs: Long? = null
        private set
    var lastSample: DrivingSample? = null
        private set
    var distanceKm = 0.0
        private set
    private var samples = 0
    private var fixSamples = 0
    /** Most of the path comes from real location fixes, so it lies on real roads (not a scenario's virtual road). */
    val onRealRoads: Boolean get() = samples > 0 && fixSamples >= samples * 0.8
    /** The latest position is a live location fix (false while dead-reckoning: no fix, or a fix that stopped moving). */
    val onFixNow: Boolean get() = positions.onFix

    val lateralAcc: Float get() = lateral.lateralAcc
    val smoothedSteering: Float get() = lateral.smoothedSteering
    val durationMs: Long get() = (lastSample?.timeMs ?: 0L) - (startMs ?: 0L)
    val laneChangeCount: Int get() = _events.count { it is LaneChange }

    /** Events that count toward a rule set's scores: its own speed events and every steering event. */
    fun events(ruleId: String): List<DrivingEvent> = _events.filter { it.isSteering || it.ruleId == ruleId }

    fun speedPenalty(ruleId: String): Float = _events.filter { it.ruleId == ruleId }.sumPenalty()
    val steeringPenalty: Float get() = _events.filter { it.isSteering }.sumPenalty()

    fun baselineScore(ruleId: String): Int = toScore(speedPenalty(ruleId))
    fun steerScore(ruleId: String): Int = toScore(speedPenalty(ruleId) + steeringPenalty)

    /** Returns every event detected on this sample (all rule sets), usually none. */
    fun onSample(s: DrivingSample): List<DrivingEvent> {
        if (startMs == null) startMs = s.timeMs
        lastSample?.let { prev ->
            val dtMs = (s.timeMs - prev.timeMs).coerceIn(0L, 1000L)
            distanceKm += s.speedKmh * dtMs / 3_600_000.0
        }
        lastSample = s

        val detected = mutableListOf<DrivingEvent>()
        detected += lateral.onSample(s)
        turns.onSample(s, lateral.smoothedSteering, lateral.lateralAcc)?.let { detected += it }
        val yaw = yawRateDegS(s.speedKmh, lateral.smoothedSteering, cfg)
        maneuvers.add(s.timeMs, yaw, lateral.lateralAcc, s.speedKmh)
        positions.onSample(s.timeMs, s.speedKmh, yaw, s.location)
        samples++
        if (positions.onFix) fixSamples++
        if (_track.isEmpty() || s.timeMs - _track.last().timeMs >= TRACK_INTERVAL_MS) {
            _track += TrackPoint(s.timeMs, positions.position, s.speedKmh, s.speedLimitKmh, s.road)
        }
        speedDetectors.forEach { (longitudinal, speeding) ->
            longitudinal.onSample(s)?.let { detected += it }
            speeding.onSample(s)?.let { detected += it }
        }
        _events += detected
        return detected
    }

    /** Time span a steering event covers, for asking the classifier about it. */
    fun spanOf(e: DrivingEvent): LongRange? = when (e) {
        is LaneChange -> e.timeMs..(e.timeMs + e.durationMs)
        is Cornering -> e.timeMs..(e.timeMs + e.durationMs)
        is IntersectionTurn -> e.timeMs..(e.timeMs + e.durationMs)
        is LaneDeparture -> e.timeMs..(e.timeMs + e.durationMs)
        else -> null
    }

    /** What the classifier thinks a steering event was; null for speed events, without a model, or not yet known. */
    fun aiVerdict(e: DrivingEvent): AiVerdict? = spanOf(e)?.let { maneuvers.verdict(it.first, it.last) }

    /** Track point nearest to [timeMs] (within a second), or the last one. */
    fun trackAt(timeMs: Long): TrackPoint? {
        if (_track.isEmpty()) return null
        val i = _track.binarySearchBy(timeMs) { it.timeMs }
        val idx = if (i >= 0) i else (-i - 1).coerceAtMost(_track.size - 1)
        val before = _track.getOrNull(idx - 1)
        val at = _track[idx]
        return if (before != null && timeMs - before.timeMs < at.timeMs - timeMs) before else at
    }

    /** Navigation road information around [timeMs] (track points are a second apart); null without guidance. */
    fun roadAt(timeMs: Long): RoadInfo? = trackAt(timeMs)?.road

    private fun List<DrivingEvent>.sumPenalty() = sumOf { it.penalty.toDouble() }.toFloat()
    private fun toScore(penalty: Float) = (100f - penalty).roundToInt().coerceIn(0, 100)

    companion object {
        /** Fine enough to show a lane change's S-shape when the report map zooms in. */
        const val TRACK_INTERVAL_MS = 200L
    }
}

data class TrackPoint(val timeMs: Long, val point: GeoPoint, val speedKmh: Float, val speedLimitKmh: Float?, val road: RoadInfo? = null)
