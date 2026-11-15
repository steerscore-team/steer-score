package com.iclab.steerscore.core

import kotlin.math.roundToInt

/**
 * One turn-by-turn point of a guided route: how far along it is (metres from the car), what kind of place, the manoeuvre
 * in words ("좌회전") and the navigation's own description ("판교IC 방면").
 */
data class RouteStep(val atM: Double, val situation: Situation?, val turn: String, val description: String) {
    /** The place the description names: "광명역IC - 광명역(KTX) 방면" → "광명역IC". */
    val place: String get() = description.substringBefore(" - ").removeSuffix(" 방면").substringBefore("(").trim()
}

/**
 * What a route exposes the driver to, in the units the advisor predicts with: km of expressway and of general road,
 * and how many times the route has each navigation situation (IC entry, merge, IC exit, fork, intersection turn,
 * roundabout, toll). From the navigation's route (NaviHelper TBT list + destination info) or from the demo data.
 */
data class RouteProfile(
    /** RouteOption names that give this route (several options often give the same one). */
    val options: List<String>,
    val distanceKm: Double,
    val timeMin: Int,
    /** -1 when unknown (the navigation's remaining-route info says 0 even on toll roads). */
    val tollWon: Int,
    val expresswayKm: Double,
    val counts: Map<Situation, Int>,
    /** False when only distance, time and toll are known (no turn-by-turn list from the navigation). */
    val composition: Boolean = true,
) {
    val generalKm: Double get() = (distanceKm - expresswayKm).coerceAtLeast(0.0)

    fun amount(e: Exposure): Double = when (e) {
        Exposure.EXPRESSWAY_KM -> expresswayKm
        Exposure.GENERAL_KM -> generalKm
        else -> (counts[e.situation] ?: 0).toDouble()
    }
}

/** The exposure units: per km of a road class for driving along, per occurrence for the navigation situations. */
enum class Exposure(val label: String, val unit: String, val situation: Situation? = null, val roadClass: RoadClass? = null) {
    EXPRESSWAY_KM("고속도로 주행", "km", roadClass = RoadClass.EXPRESSWAY),
    GENERAL_KM("일반도로 주행", "km", roadClass = RoadClass.GENERAL),
    IC_ENTRY("IC 진입", "회", Situation.IC_ENTRY),
    MERGE("합류", "회", Situation.MERGE),
    IC_EXIT("IC 진출", "회", Situation.IC_EXIT),
    FORK("분기", "회", Situation.FORK),
    INTERSECTION("교차로 회전", "회", Situation.INTERSECTION),
    ROUNDABOUT("회전교차로", "회", Situation.ROUNDABOUT),
    TOLL("톨게이트", "회", Situation.TOLL);

    /** Prior strength for the shrinkage: how much exposure the population average counts for. */
    val priorWeight: Double get() = if (unit == "km") 30.0 else 3.0

    companion object {
        /** Where a past deduction happened: its navigation situation, or the road class when driving along. */
        fun of(situation: Situation, roadClass: RoadClass?): Exposure =
            entries.firstOrNull { it.situation != null && it.situation == situation }
                ?: if (roadClass == RoadClass.EXPRESSWAY) EXPRESSWAY_KM else GENERAL_KM
    }
}

data class PastEvent(val cause: Cause, val situation: Situation, val roadClass: RoadClass?, val penalty: Float)

data class PastTrip(val date: String, val od: String, val option: String, val route: RouteProfile, val score: Int, val events: List<PastEvent>)

data class Persona(val id: String, val name: String, val summary: String, val trips: List<PastTrip>)

data class OdPair(val id: String, val from: String, val to: String, val kind: String, val routes: List<RouteProfile>)

/**
 * Predicts a driver's SteerScore on each candidate route from their own past drives, rule based:
 *
 * 1. From the history, the points lost per unit of exposure, for each exposure (expressway km, general km, IC entry,
 *    merge, IC exit, fork, intersection turn, roundabout, toll) and cause. This is the "events per distance /
 *    per situation" rate used by commercial telematics and the KOTSA per-100 km statistics, split by where they happen.
 * 2. Rates from little exposure are pulled toward the average of all drivers (empirical-Bayes shrinkage:
 *    (points + w × average) / (exposure + w), w = 30 km or 3 occurrences), so 1 lucky merge does not mean "never".
 * 3. Expected deduction of a route = Σ rate × that route's exposure; predicted score = 100 − expected deduction, the
 *    same formula as the trip score.
 */
class RouteAdvisor(private val driver: List<PastTrip>, population: List<PastTrip> = driver) {

    data class Part(val exposure: Exposure, val cause: Cause, val points: Double)

    data class Prediction(val route: RouteProfile, val score: Int, val expectedPenalty: Double, val parts: List<Part>) {
        /** The largest expected deductions, for "why". */
        val top: List<Part> get() = parts.filter { it.points >= 0.3 }.sortedByDescending { it.points }.take(3)
    }

    private val exposure: Map<Exposure, Double> = totals(driver)
    private val points: Map<Pair<Exposure, Cause>, Double> = pointsOf(driver)
    private val popExposure = totals(population)
    private val popPoints = pointsOf(population)

    /** Points lost per unit of [e] for [cause], shrunk toward the population. */
    fun rate(e: Exposure, cause: Cause): Double {
        val popRate = (popPoints[e to cause] ?: 0.0) / (popExposure[e] ?: 0.0).coerceAtLeast(1e-9)
        val w = e.priorWeight
        return ((points[e to cause] ?: 0.0) + w * popRate) / ((exposure[e] ?: 0.0) + w)
    }

    fun predict(route: RouteProfile): Prediction {
        if (!route.composition) {
            // Only the length is known: the driver's points lost per km over all their drives
            val perKm = Cause.entries.associateWith { c ->
                Exposure.entries.sumOf { e -> points[e to c] ?: 0.0 } / (exposureOf(Exposure.EXPRESSWAY_KM) + exposureOf(Exposure.GENERAL_KM)).coerceAtLeast(1.0)
            }
            val parts = perKm.filterValues { it > 0 }.map { (c, r) -> Part(Exposure.GENERAL_KM, c, r * route.distanceKm) }
            val expected = parts.sumOf { it.points }
            return Prediction(route, (100.0 - expected).roundToInt().coerceIn(0, 100), expected, parts)
        }
        val parts = Exposure.entries.flatMap { e ->
            val amount = route.amount(e)
            if (amount <= 0.0) emptyList() else Cause.entries.map { c -> Part(e, c, rate(e, c) * amount) }.filter { it.points > 0.0 }
        }
        val expected = parts.sumOf { it.points }
        return Prediction(route, (100.0 - expected).roundToInt().coerceIn(0, 100), expected, parts)
    }

    fun predictAll(routes: List<RouteProfile>): List<Prediction> = routes.map(::predict)

    /** How much the history covers, for the "based on" line. */
    val tripCount: Int get() = driver.size
    fun exposureOf(e: Exposure): Double = exposure[e] ?: 0.0

    companion object {
        private fun totals(trips: List<PastTrip>): Map<Exposure, Double> =
            Exposure.entries.associateWith { e -> trips.sumOf { it.route.amount(e) } }

        private fun pointsOf(trips: List<PastTrip>): Map<Pair<Exposure, Cause>, Double> =
            trips.flatMap { it.events }.groupBy { Exposure.of(it.situation, it.roadClass) to it.cause }
                .mapValues { (_, es) -> es.sumOf { it.penalty.toDouble() } }

        /** The demo data (virtual drives, generated offline). */
        fun loadDemo(): RouteDemo = RouteDemo.parse(
            RouteAdvisor::class.java.getResourceAsStream("/routes/personas.txt")!!.bufferedReader(Charsets.UTF_8).readLines(),
        )

        /** Readable names of the navigation's RouteOption values. */
        val OPTION_LABELS = linkedMapOf(
            "RECOMMENDED" to "추천", "SHORT_TIME" to "최소시간", "SHORT_DISTANCE" to "최단거리", "HIGHWAY" to "고속도로 우선",
            "FREE" to "무료도로", "COMFORTABLE" to "편한길", "SCHOOL_ZONE" to "어린이보호구역 회피",
        )

        fun optionLabel(options: List<String>): String = options.joinToString(" · ") { OPTION_LABELS[it] ?: it }
    }
}

/** The virtual personas and origin → destination pairs of the demo. */
data class RouteDemo(val ods: List<OdPair>, val personas: List<Persona>) {
    val allTrips: List<PastTrip> get() = personas.flatMap { it.trips }

    fun advisor(persona: Persona) = RouteAdvisor(persona.trips, allTrips)

    companion object {
        private val COUNT_ORDER = listOf(
            Situation.IC_ENTRY, Situation.MERGE, Situation.IC_EXIT, Situation.FORK,
            Situation.INTERSECTION, Situation.ROUNDABOUT, Situation.TOLL,
        )

        fun parse(lines: List<String>): RouteDemo {
            val ods = linkedMapOf<String, Triple<String, String, String>>()
            val routes = linkedMapOf<String, MutableList<RouteProfile>>()
            val personas = linkedMapOf<String, Pair<String, String>>()
            val trips = linkedMapOf<Pair<String, Int>, PastTrip>()
            val events = linkedMapOf<Pair<String, Int>, MutableList<PastEvent>>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val f = line.split('|')
                when (f[0]) {
                    "OD" -> ods[f[1]] = Triple(f[2], f[3], f[4])
                    "ROUTE" -> routes.getOrPut(f[1]) { mutableListOf() } += RouteProfile(
                        options = f[2].split(','),
                        distanceKm = f[3].toDouble(), timeMin = f[4].toInt(), tollWon = f[5].toInt(), expresswayKm = f[6].toDouble(),
                        counts = COUNT_ORDER.mapIndexed { i, s -> s to f[7 + i].toInt() }.toMap(),
                    )
                    "PERSONA" -> personas[f[1]] = f[2] to f[3]
                    "TRIP" -> {
                        val route = routes.getValue(f[4]).first { f[5] in it.options }
                        trips[f[1] to f[2].toInt()] = PastTrip(f[3], f[4], f[5], route, f[6].toInt(), emptyList())
                    }
                    "EVENT" -> events.getOrPut(f[1] to f[2].toInt()) { mutableListOf() } += PastEvent(
                        Cause.valueOf(f[3]), Situation.parse(f[4]), RoadClass.valueOf(f[5]), f[6].toFloat(),
                    )
                }
            }
            return RouteDemo(
                ods = ods.map { (id, t) -> OdPair(id, t.first, t.second, t.third, routes[id].orEmpty()) },
                personas = personas.map { (id, p) ->
                    Persona(id, p.first, p.second, trips.filterKeys { it.first == id }.map { (k, t) -> t.copy(events = events[k].orEmpty()) })
                },
            )
        }
    }
}
