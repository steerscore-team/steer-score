package com.iclab.steerscore.core

/**
 * The situation an event happened in. Without navigation only the steering tells it (intersection turn, curve,
 * straight road). With route guidance the navigation adds where the car is on the route: IC entry, merge, IC exit,
 * fork, roundabout, toll gate ([RoadInfo]). The app never guesses a road class from speed.
 */
enum class Situation(val label: String) {
    STRAIGHT("직선 도로"),
    CURVE("커브"),
    INTERSECTION("교차로"),
    IC_ENTRY("IC 진입로"),
    MERGE("합류 구간"),
    IC_EXIT("IC 진출로"),
    FORK("분기 구간"),
    ROUNDABOUT("회전교차로"),
    TOLL("톨게이트");

    companion object {
        private val STEERING_ONLY = setOf(STRAIGHT, CURVE, INTERSECTION)

        /** A navigation situation wins (an IC ramp curve is "IC 진입로"); otherwise the maneuver decides. */
        fun of(e: DrivingEvent, road: RoadInfo? = null): Situation {
            val navi = road?.situation?.takeIf { it !in STEERING_ONLY }
            return when {
                e is IntersectionTurn -> if (navi == ROUNDABOUT) ROUNDABOUT else INTERSECTION
                navi != null -> navi
                e is Cornering || (e is LaneChange && e.onCurve) -> CURVE
                else -> STRAIGHT
            }
        }

        /** Reads a stored name; logs written before 2026-11-14 used HIGHWAY / URBAN for straight stretches. */
        fun parse(name: String): Situation = entries.firstOrNull { it.name == name } ?: STRAIGHT
    }
}

/** One deduction as kept in the trip log: why, which habit would fix it, where. */
data class EventRecord(
    val offsetMs: Long,
    /** Baseline rule set of a speed event; null for steering events, which count under every baseline. */
    val ruleId: String?,
    val cause: Cause,
    val penalty: Float,
    val habits: Map<Habit, Float>,
    val situation: Situation,
    val point: GeoPoint,
    /** From the navigation; null when the trip had no route guidance there. */
    val roadClass: RoadClass? = null,
)

/** Everything the cumulative analysis needs from one finished trip. */
data class TripLog(
    val endedAt: Long,
    val label: String,
    val distanceKm: Double,
    val durationMs: Long,
    val baseline: Map<String, Int>,
    val steer: Map<String, Int>,
    val events: List<EventRecord>,
    /** Track thinned to about one point every [ROUTE_INTERVAL_MS], for drawing the route. */
    val route: List<GeoPoint>,
    /** The navigation reported road information during the trip (route guidance, or scenario ⑦). */
    val roadInfo: Boolean = false,
    /** The route was recorded from real location fixes: it can be drawn over the real road map. */
    val gps: Boolean = false,
) {
    fun eventsFor(ruleId: String) = events.filter { it.ruleId == null || it.ruleId == ruleId }

    companion object {
        const val ROUTE_INTERVAL_MS = 3000L

        fun of(a: TripAnalyzer, label: String, endedAt: Long, cfg: AnalyzerConfig = AnalyzerConfig(), virtualRoad: Boolean = false): TripLog {
            val start = a.startMs ?: 0L
            val events = a.allEvents.mapNotNull { e ->
                val cause = Cause.of(e) ?: return@mapNotNull null
                val at = a.trackAt(e.timeMs)
                val road = a.roadAt(e.timeMs)
                EventRecord(
                    offsetMs = e.timeMs - start,
                    ruleId = e.ruleId,
                    cause = cause,
                    penalty = e.penalty,
                    habits = habitShares(e, cfg),
                    situation = Situation.of(e, road),
                    point = at?.point ?: Scenarios.DEFAULT_START.point,
                    roadClass = road?.roadClass,
                )
            }
            var lastMs: Long? = null
            val route = a.track.filter { p ->
                val keep = lastMs.let { it == null || p.timeMs - it >= ROUTE_INTERVAL_MS }
                if (keep) lastMs = p.timeMs
                keep
            }.map { it.point } + listOfNotNull(a.track.lastOrNull()?.point)
            return TripLog(
                endedAt = endedAt,
                label = label,
                distanceKm = a.distanceKm,
                durationMs = a.durationMs,
                baseline = a.ruleSets.associate { it.id to a.baselineScore(it.id) },
                steer = a.ruleSets.associate { it.id to a.steerScore(it.id) },
                events = events,
                route = route,
                roadInfo = a.track.any { it.road != null },
                gps = a.onRealRoads && !virtualRoad,
            )
        }
    }
}

data class SituationStat(val type: Situation, val count: Int, val penalty: Float, val share: Float)

/** Points lost per road class, over the events whose road class the navigation knew. */
data class RoadClassStat(val type: RoadClass, val count: Int, val penalty: Float, val share: Float)

/** A spot where deductions keep happening, possibly across several trips. */
data class Hotspot(
    val center: GeoPoint,
    val count: Int,
    val trips: Int,
    val penalty: Float,
    val topCause: Cause,
    val situation: Situation,
    val steering: Boolean,
    val roadClass: RoadClass? = null,
) {
    /** "합류 구간", or the road class on a plain straight ("고속도로"), else the steering situation. */
    val place: String get() = if (situation == Situation.STRAIGHT && roadClass != null) roadClass.label else situation.label
}

/**
 * One habit over all trips: [gain] is how many points it cost in total, [perTrip] the average per trip, and
 * [topSituation] where it happened most often (with that road type's share of its events).
 */
data class HabitInsight(
    val habit: Habit,
    val gain: Float,
    val count: Int,
    val perTrip: Float,
    val topSituation: Situation?,
    val topSituationShare: Float,
)

/**
 * Trips whose routes touch, drawn together on one map: driving in different places on one map shrinks every route
 * to a few pixels. [labels] are the trips' names, latest first.
 */
data class MapArea(
    val routes: List<List<GeoPoint>>,
    val hotspots: List<Hotspot>,
    val trips: Int,
    val distanceKm: Double,
    val labels: List<String>,
    /** Every trip on this map was recorded from real location fixes: draw the real road map underneath. */
    val gps: Boolean = false,
)

class Insights private constructor(
    val ruleId: String,
    val tripCount: Int,
    val distanceKm: Double,
    val avgBaseline: Float,
    val avgSteer: Float,
    /** The steering situations always, navigation situations when they occurred; largest share first. */
    val situations: List<SituationStat>,
    /** Over events whose road class the navigation knew; empty without route guidance. */
    val roadClasses: List<RoadClassStat>,
    /** Trips with navigation road information. */
    val roadInfoTrips: Int,
    /** Separate maps, the area of the latest trip first, then the most driven. */
    val areas: List<MapArea>,
    /** Largest gain first: the improvement plan. */
    val habits: List<HabitInsight>,
    /** SteerScore of each trip, oldest first. */
    val trend: List<Int>,
) {
    val totalPenalty: Float get() = situations.sumOf { it.penalty.toDouble() }.toFloat()
    val goal: HabitInsight? get() = habits.firstOrNull()
    /** The latest trip's area: largest hotspot first. */
    val hotspots: List<Hotspot> get() = areas.firstOrNull()?.hotspots.orEmpty()
    val routes: List<List<GeoPoint>> get() = areas.firstOrNull()?.routes.orEmpty()

    /** Average SteerScore of the last [n] trips against the trips before them; null with too few trips. */
    fun recentChange(n: Int = 3): Pair<Float, Float>? {
        if (trend.size < n + 1) return null
        return trend.dropLast(n).average().toFloat() to trend.takeLast(n).average().toFloat()
    }

    companion object {
        /** Events closer than this belong to one hotspot. */
        const val HOTSPOT_RADIUS_M = 150.0
        /** Two trips whose routes come this close share a map. */
        const val AREA_LINK_M = 300.0

        /** [logs] in any order. */
        fun of(logs: List<TripLog>, ruleId: String): Insights {
            val trips = logs.sortedBy { it.endedAt }
            val records = trips.flatMap { t -> t.eventsFor(ruleId).map { t to it } }

            val situations = situationStats(records.map { it.second })
            val classed = records.map { it.second }.filter { it.roadClass != null }
            val classedTotal = classed.sumOf { it.penalty.toDouble() }.toFloat()
            val roadClasses = if (classed.isEmpty()) emptyList() else RoadClass.entries.map { type ->
                val rs = classed.filter { it.roadClass == type }
                val p = rs.sumOf { it.penalty.toDouble() }.toFloat()
                RoadClassStat(type, rs.size, p, if (classedTotal > 0f) p / classedTotal else 0f)
            }.sortedByDescending { it.penalty }

            val habits = Habit.entries.mapNotNull { h ->
                val rs = records.filter { h in it.second.habits }
                if (rs.isEmpty()) return@mapNotNull null
                val gain = rs.sumOf { it.second.habits.getValue(h).toDouble() }.toFloat()
                val byRoad = rs.groupingBy { it.second.situation }.eachCount().maxByOrNull { it.value }
                HabitInsight(
                    habit = h,
                    gain = gain,
                    count = rs.size,
                    perTrip = gain / trips.size,
                    topSituation = byRoad?.key,
                    topSituationShare = (byRoad?.value ?: 0).toFloat() / rs.size,
                )
            }.sortedByDescending { it.gain }

            // Maps only for drives recorded from real location fixes: a virtual scenario road or a path dead-reckoned
            // without GPS has no real place to draw (they still count in every total and habit above)
            val areas = areasOf(trips.filter { it.gps }).map { group ->
                MapArea(
                    routes = group.map { it.route },
                    hotspots = hotspots(group.flatMap { t -> t.eventsFor(ruleId).map { t to it } }),
                    trips = group.size,
                    distanceKm = group.sumOf { it.distanceKm },
                    labels = group.reversed().map { it.label },
                    gps = group.all { it.gps },
                )
            }

            return Insights(
                ruleId = ruleId,
                tripCount = trips.size,
                distanceKm = trips.sumOf { it.distanceKm },
                avgBaseline = trips.mapNotNull { it.baseline[ruleId] }.average().toFloat().takeIf { !it.isNaN() } ?: 100f,
                avgSteer = trips.mapNotNull { it.steer[ruleId] }.average().toFloat().takeIf { !it.isNaN() } ?: 100f,
                situations = situations,
                roadClasses = roadClasses,
                roadInfoTrips = trips.count { it.roadInfo },
                areas = areas,
                habits = habits,
                trend = trips.mapNotNull { it.steer[ruleId] },
            )
        }

        /** Points lost per situation: the three steering situations always, the navigation ones when they occurred. */
        fun situationStats(events: List<EventRecord>): List<SituationStat> {
            val total = events.sumOf { it.penalty.toDouble() }.toFloat()
            return Situation.entries.mapNotNull { type ->
                val rs = events.filter { it.situation == type }
                if (rs.isEmpty() && type !in setOf(Situation.STRAIGHT, Situation.CURVE, Situation.INTERSECTION)) return@mapNotNull null
                val p = rs.sumOf { it.penalty.toDouble() }.toFloat()
                SituationStat(type, rs.size, p, if (total > 0f) p / total else 0f)
            }.sortedByDescending { it.penalty }
        }

        /**
         * Groups [trips] (oldest first) whose routes touch, directly or through another trip. The latest trip's group
         * comes first, then the others by trip count. Each group keeps the trips oldest first.
         */
        fun areasOf(trips: List<TripLog>): List<List<TripLog>> {
            val parent = IntArray(trips.size) { it }
            fun root(i: Int): Int = if (parent[i] == i) i else root(parent[i]).also { parent[i] = it }
            val boxes = trips.map { box(it.route + it.events.map { e -> e.point }) }
            for (i in trips.indices) for (j in i + 1 until trips.size) {
                if (root(i) == root(j)) continue
                // A virtual scenario road never shares a map with real roads, even where it happens to cross them:
                // the real-road map is drawn over the real road map, the virtual one is not
                if (trips[i].gps != trips[j].gps) continue
                val a = boxes[i] ?: continue
                val b = boxes[j] ?: continue
                if (!a.near(b)) continue
                if (trips[i].route.any { p -> trips[j].route.any { q -> p.distanceM(q) <= AREA_LINK_M } }) parent[root(j)] = root(i)
            }
            val groups = trips.indices.groupBy { root(it) }.values.map { ids -> ids.map { trips[it] } }
            val latest = trips.lastOrNull()
            return groups.sortedWith(compareByDescending<List<TripLog>> { latest in it }.thenByDescending { it.size })
        }

        /** Bounding box in degrees, grown by [AREA_LINK_M] for the cheap "could these touch" check. */
        private class Box(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
            fun near(o: Box) = minLat <= o.maxLat && o.minLat <= maxLat && minLon <= o.maxLon && o.minLon <= maxLon
        }

        private fun box(points: List<GeoPoint>): Box? {
            if (points.isEmpty()) return null
            val dLat = AREA_LINK_M / GeoPoint.METERS_PER_DEG_LAT
            val dLon = dLat / kotlin.math.cos(Math.toRadians(points.first().lat))
            return Box(points.minOf { it.lat } - dLat, points.maxOf { it.lat } + dLat, points.minOf { it.lon } - dLon, points.maxOf { it.lon } + dLon)
        }

        /** Greedy clustering, biggest deductions first, so each hotspot is centered on its worst event. */
        private fun hotspots(records: List<Pair<TripLog, EventRecord>>): List<Hotspot> {
            val clusters = mutableListOf<MutableList<Pair<TripLog, EventRecord>>>()
            for (r in records.sortedByDescending { it.second.penalty }) {
                val home = clusters.firstOrNull { it.first().second.point.distanceM(r.second.point) <= HOTSPOT_RADIUS_M }
                if (home != null) home += r else clusters += mutableListOf(r)
            }
            return clusters.map { c ->
                val events = c.map { it.second }
                val top = events.groupBy { it.cause }.maxBy { (_, es) -> es.sumOf { it.penalty.toDouble() } }.key
                Hotspot(
                    center = events.first().point,
                    count = events.size,
                    trips = c.map { it.first.endedAt }.distinct().size,
                    penalty = events.sumOf { it.penalty.toDouble() }.toFloat(),
                    topCause = top,
                    situation = events.groupingBy { it.situation }.eachCount().maxBy { it.value }.key,
                    steering = top.steering,
                    roadClass = events.mapNotNull { it.roadClass }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                )
            }.sortedByDescending { it.penalty }
        }
    }
}
