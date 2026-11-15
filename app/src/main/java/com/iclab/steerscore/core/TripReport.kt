package com.iclab.steerscore.core

import java.util.Locale
import kotlin.math.roundToInt

/** Why points were lost, grouped the way a driver would fix them. */
enum class Cause(val label: String, val steering: Boolean) {
    UNSIGNALED_ABRUPT_LANE("깜빡이 없는 급차선변경", true),
    ABRUPT_LANE("급차선변경", true),
    UNSIGNALED_LANE("깜빡이 없는 차선변경", true),
    UNSIGNALED_TURN("깜빡이 없는 회전", true),
    LANE_DEPARTURE("차선 이탈 후 복귀", true),
    HARD_TURN("급커브·급회전", true),
    HARD_BRAKE("급감속", false),
    HARD_ACCEL("급가속", false),
    SPEEDING("과속", false);

    companion object {
        /** Null for events that cost nothing (a normal lane change, curve or turn). */
        fun of(e: DrivingEvent): Cause? {
            if (e.penalty == 0f) return null
            return when (e) {
                is LaneChange -> when {
                    e.abrupt && !e.signaled -> UNSIGNALED_ABRUPT_LANE
                    e.abrupt -> ABRUPT_LANE
                    else -> UNSIGNALED_LANE
                }
                is IntersectionTurn -> if (!e.signaled) UNSIGNALED_TURN else HARD_TURN
                is LaneDeparture -> LANE_DEPARTURE
                is Cornering -> HARD_TURN
                is HardBraking -> HARD_BRAKE
                is HardAcceleration -> HARD_ACCEL
                is Speeding -> SPEEDING
            }
        }
    }
}

data class CauseStat(val cause: Cause, val count: Int, val penalty: Float)

/** Both scores right after the deduction at [offsetMs] from the start of the trip. */
data class ScoreStep(val offsetMs: Long, val baseline: Int, val steer: Int)

/** Abrupt lane changes back to back ("칼치기"), each still deducted on its own. */
data class WeavingRun(val startOffsetMs: Long, val endOffsetMs: Long, val count: Int, val penalty: Float)

/** One habit to work on next time and the points it would have saved on this trip. */
data class Tip(val habit: Habit, val gain: Float, val detail: String)

/** One row of the report's event list: an event with where it happened and what the road was like there. */
data class ReportItem(
    val offsetMs: Long,
    val event: DrivingEvent,
    val situation: Situation,
    val roadClass: RoadClass?,
    val point: GeoPoint?,
) {
    /** "합류 구간", "고속도로" on a plain straight, else the steering situation. */
    val place: String get() = if (situation == Situation.STRAIGHT && roadClass != null) roadClass.label else situation.label
}

/** Deductions on one road type (or, without navigation, in one situation) and what caused them. */
data class RoadGroup(val label: String, val count: Int, val penalty: Float, val causes: List<CauseStat>)

enum class Habit(val goal: String) {
    SIGNAL("차선을 바꾸거나 돌기 전에 깜빡이 먼저"),
    SLOW_LANE_CHANGE("차선변경은 4초 이상에 걸쳐 천천히"),
    EARLY_BRAKING("앞을 멀리 보고 미리 천천히 감속"),
    SMOOTH_ACCEL("출발·가속은 부드럽게"),
    KEEP_LIMIT("제한속도 안에서 일정하게"),
    SLOW_INTO_TURNS("커브·회전 전에 충분히 감속"),
    KEEP_LANE("앞을 보고 차선 안에서 똑바로"),
}

/**
 * The after-trip report: when the score dropped and why, under one baseline rule set. Built from the events the
 * analyzer already scored, so its totals always match the scores on screen.
 */
class TripReport private constructor(
    val ruleId: String,
    val durationMs: Long,
    val distanceKm: Double,
    val baselineScore: Int,
    val steerScore: Int,
    val speedPenalty: Float,
    val steeringPenalty: Float,
    /** Events of this rule set with their offset from the trip start, in time order. */
    val events: List<Pair<Long, DrivingEvent>>,
    /** Starts at (0, 100, 100) and steps at every deduction. */
    val steps: List<ScoreStep>,
    /** Sorted by points lost, largest first. */
    val causes: List<CauseStat>,
    val weaving: List<WeavingRun>,
    /** Sorted by points saved, largest first. */
    val tips: List<Tip>,
    /** Points lost per situation (steering situations always, navigation situations when known). */
    val situations: List<SituationStat>,
    /** Points lost per road class; empty unless the navigation gave road information. */
    val roadClasses: List<RoadClassStat>,
    /** This trip as the cumulative analysis sees it, for the route map. */
    val log: TripLog,
    /** Every event of this rule set (normal ones too), in time order, with its place. */
    val items: List<ReportItem>,
    /** Deductions grouped by road type when the navigation knew it, else by situation; largest first. */
    val groups: List<RoadGroup>,
    /** Deductions grouped by situation (merge section, IC exit, curve, ...); largest first. */
    val situationGroups: List<RoadGroup>,
    /** Track of the trip (about 5 points a second) for the report map. */
    val track: List<TrackPoint>,
) {
    /** The navigation gave road information during the trip, so the route view is worth showing. */
    val hasRoadInfo: Boolean get() = log.roadInfo
    val topCauses: List<CauseStat> get() = causes.take(3)
    val laneChangeCount: Int get() = events.count { it.second is LaneChange }

    companion object {
        /** Abrupt lane changes closer than this (end of one to start of the next) count as weaving. */
        const val WEAVING_GAP_MS = 3000L

        fun of(a: TripAnalyzer, ruleId: String, cfg: AnalyzerConfig = AnalyzerConfig()): TripReport {
            val start = a.startMs ?: 0L
            val events = a.events(ruleId).sortedBy { it.timeMs }.map { (it.timeMs - start) to it }

            var speed = 0.0
            var steer = 0.0
            val steps = mutableListOf(ScoreStep(0, 100, 100))
            for ((t, e) in events) {
                if (e.penalty == 0f) continue
                if (e.isSteering) steer += e.penalty else speed += e.penalty
                steps += ScoreStep(t, score(speed), score(speed + steer))
            }

            val causes = events.mapNotNull { (_, e) -> Cause.of(e)?.let { it to e } }
                .groupBy({ it.first }, { it.second })
                .map { (c, es) -> CauseStat(c, es.size, es.sumOf { it.penalty.toDouble() }.toFloat()) }
                .sortedByDescending { it.penalty }

            val log = TripLog.of(a, "", 0L, cfg)
            val one = Insights.of(listOf(log), ruleId)
            val items = events.map { (t, e) ->
                val road = a.roadAt(e.timeMs)
                ReportItem(t, e, Situation.of(e, road), road?.roadClass, a.trackAt(e.timeMs)?.point)
            }
            val byRoadClass = log.roadInfo
            val deductions = items.filter { it.event.penalty > 0f }
            val groups = group(deductions) { if (byRoadClass) it.roadClass?.label ?: it.situation.label else it.situation.label }
            val situationGroups = group(deductions) { it.situation.label }
            return TripReport(
                ruleId = ruleId,
                durationMs = a.durationMs,
                distanceKm = a.distanceKm,
                baselineScore = a.baselineScore(ruleId),
                steerScore = a.steerScore(ruleId),
                speedPenalty = a.speedPenalty(ruleId),
                steeringPenalty = a.steeringPenalty,
                events = events,
                steps = steps,
                causes = causes,
                weaving = weavingRuns(events),
                tips = tips(events.map { it.second }, cfg),
                situations = one.situations,
                roadClasses = one.roadClasses,
                log = log,
                items = items,
                groups = groups,
                situationGroups = situationGroups,
                track = a.track.toList(),
            )
        }

        private fun group(items: List<ReportItem>, key: (ReportItem) -> String): List<RoadGroup> =
            items.groupBy(key).map { (label, its) ->
                val causes = its.mapNotNull { i -> Cause.of(i.event)?.let { it to i.event } }
                    .groupBy({ it.first }, { it.second })
                    .map { (c, es) -> CauseStat(c, es.size, es.sumOf { it.penalty.toDouble() }.toFloat()) }
                    .sortedByDescending { it.penalty }
                RoadGroup(label, its.size, its.sumOf { it.event.penalty.toDouble() }.toFloat(), causes)
            }.sortedByDescending { it.penalty }

        private fun score(penalty: Double) = (100.0 - penalty).roundToInt().coerceIn(0, 100)

        private fun weavingRuns(events: List<Pair<Long, DrivingEvent>>): List<WeavingRun> {
            val runs = mutableListOf<WeavingRun>()
            var run = mutableListOf<Pair<Long, LaneChange>>()
            fun close() {
                if (run.size >= 2) {
                    val last = run.last()
                    runs += WeavingRun(
                        run.first().first, last.first + last.second.durationMs, run.size,
                        run.sumOf { it.second.penalty.toDouble() }.toFloat(),
                    )
                }
                run = mutableListOf()
            }
            for ((t, e) in events) {
                if (e !is LaneChange) continue
                if (!e.abrupt) {
                    close()
                    continue
                }
                val prev = run.lastOrNull()
                if (prev != null && t - (prev.first + prev.second.durationMs) > WEAVING_GAP_MS) close()
                run += t to e
            }
            close()
            return runs
        }

        /** Splits each deduction into the habits behind it, so a combined event credits both fixes. */
        private fun tips(events: List<DrivingEvent>, cfg: AnalyzerConfig): List<Tip> {
            val gain = mutableMapOf<Habit, Float>()
            val count = mutableMapOf<Habit, Int>()
            for (e in events) {
                for ((h, points) in habitShares(e, cfg)) {
                    gain[h] = (gain[h] ?: 0f) + points
                    count[h] = (count[h] ?: 0) + 1
                }
            }
            val abrupt = events.filterIsInstance<LaneChange>().filter { it.abrupt }
            val normal = events.filterIsInstance<LaneChange>().filter { !it.abrupt }
            return gain.map { (h, g) ->
                val n = count.getValue(h)
                val detail = when (h) {
                    Habit.SIGNAL -> "깜빡이 없이 ${n}번"
                    Habit.SLOW_LANE_CHANGE -> buildString {
                        append("급차선변경 ${n}번, 평균 %.1f초".format(Locale.US, abrupt.map { it.durationMs }.average() / 1000))
                        if (normal.isNotEmpty()) {
                            append(" (정상 차선변경은 %.1f초)".format(Locale.US, normal.map { it.durationMs }.average() / 1000))
                        }
                    }
                    else -> "${n}번"
                }
                Tip(h, g, detail)
            }.sortedByDescending { it.gain }
        }
    }
}

/**
 * Splits one deduction into the habits behind it, so a combined event credits both fixes: an abrupt unsignaled
 * lane change (−8) is 5 points for steering slowly plus 3 for signaling. The shares add up to the penalty.
 */
fun habitShares(e: DrivingEvent, cfg: AnalyzerConfig = AnalyzerConfig()): Map<Habit, Float> {
    if (e.penalty == 0f) return emptyMap()
    val p = cfg.penalties
    val m = e.multiplier
    return when (e) {
        is LaneChange -> buildMap {
            if (!e.signaled) put(Habit.SIGNAL, p.unsignaledLaneChange * m)
            if (e.abrupt) put(Habit.SLOW_LANE_CHANGE, p.abruptLaneChange * m)
        }
        is IntersectionTurn -> buildMap {
            if (!e.signaled) put(Habit.SIGNAL, p.unsignaledTurn * m)
            if (e.hard) put(Habit.SLOW_INTO_TURNS, p.hardCornering * m)
        }
        is Cornering -> mapOf(Habit.SLOW_INTO_TURNS to e.penalty)
        is LaneDeparture -> mapOf(Habit.KEEP_LANE to e.penalty)
        is HardBraking -> mapOf(Habit.EARLY_BRAKING to e.penalty)
        is HardAcceleration -> mapOf(Habit.SMOOTH_ACCEL to e.penalty)
        is Speeding -> mapOf(Habit.KEEP_LIMIT to e.penalty)
    }
}
