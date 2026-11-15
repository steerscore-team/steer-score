package com.iclab.steerscore.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Declarative description of a drive, rendered to 10 Hz samples. Times are in seconds, speeds in km/h,
 * steering in steering-wheel degrees (left negative). Rendering is deterministic for a given seed.
 */
class ScenarioBuilder(private val durationS: Double) {

    private data class Shape(val startS: Double, val periodS: Double, val amplitudeDeg: Double)
    private data class Curve(val startS: Double, val rampS: Double, val holdS: Double, val deg: Double)

    private var speedKeys = listOf(0.0 to 0.0)
    private val laneChanges = mutableListOf<Shape>()
    private val curves = mutableListOf<Curve>()
    private val leftSignal = mutableListOf<ClosedFloatingPointRange<Double>>()
    private val rightSignal = mutableListOf<ClosedFloatingPointRange<Double>>()
    private val braking = mutableListOf<ClosedFloatingPointRange<Double>>()

    var duskFromS: Double? = null
    var speedLimitKmh: Float? = Scenarios.SPEED_LIMIT_KMH

    /** (time, speed) keyframes, linearly interpolated. */
    fun speed(vararg keys: Pair<Double, Double>) {
        speedKeys = keys.toList()
    }

    /** One sine period on the steering wheel; a negative amplitude steers left first (a lane change to the left). */
    fun laneChange(startS: Double, periodS: Double, amplitudeDeg: Double) {
        laneChanges += Shape(startS, periodS, amplitudeDeg)
    }

    /** Trapezoid on the steering wheel: ramp in, hold, ramp out. */
    fun curve(startS: Double, rampS: Double, holdS: Double, deg: Double) {
        curves += Curve(startS, rampS, holdS, deg)
    }

    fun leftSignal(fromS: Double, toS: Double) { leftSignal += fromS..toS }
    fun rightSignal(fromS: Double, toS: Double) { rightSignal += fromS..toS }
    fun brake(fromS: Double, toS: Double) { braking += fromS..toS }

    fun build(seed: Long = 42L): List<DrivingSample> {
        val noise = Random(seed)
        val durationMs = (durationS * 1000).toLong()
        return (0..durationMs step Scenarios.SAMPLE_INTERVAL_MS).map { ms ->
            val t = ms / 1000.0
            val dusk = duskFromS?.let { t >= it } == true
            DrivingSample(
                timeMs = ms,
                speedKmh = speedAt(t).toFloat(),
                steeringDeg = (steeringAt(t) + noise.nextGaussian() * 0.3).toFloat(),
                turnSignal = when {
                    leftSignal.any { t in it } -> Turn.LEFT
                    rightSignal.any { t in it } -> Turn.RIGHT
                    else -> Turn.OFF
                },
                brakePressed = braking.any { t in it },
                conditions = Conditions(lowBeam = dusk),
                speedLimitKmh = speedLimitKmh,
            )
        }
    }

    private fun speedAt(t: Double): Double {
        val i = speedKeys.indexOfLast { it.first <= t }.coerceIn(0, speedKeys.size - 2)
        val (t0, v0) = speedKeys[i]
        val (t1, v1) = speedKeys[i + 1]
        val f = ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
        return v0 + (v1 - v0) * f
    }

    private fun steeringAt(t: Double): Double {
        val lanes = laneChanges.sumOf { lc ->
            val x = (t - lc.startS) / lc.periodS
            if (x in 0.0..1.0) lc.amplitudeDeg * sin(2 * PI * x) else 0.0
        }
        val bends = curves.sumOf { c ->
            val x = t - c.startS
            when {
                x < 0 -> 0.0
                x < c.rampS -> c.deg * x / c.rampS
                x < c.rampS + c.holdS -> c.deg
                x < 2 * c.rampS + c.holdS -> c.deg * (2 * c.rampS + c.holdS - x) / c.rampS
                else -> 0.0
            }
        }
        return lanes + bends
    }
}

data class Scenario(
    val id: String,
    val title: String,
    val description: String,
    val zones: List<EnforcementZone> = emptyList(),
    /** Made-up start point for dead reckoning, so the cumulative map can place each route's events. */
    val start: GeoStart = Scenarios.DEFAULT_START,
    val build: () -> List<DrivingSample>,
)

/**
 * Scripted drives for demos.
 *
 * The four matrix scenarios form a 2×2 comparison on one route: speed (normal / abnormal) × steering
 * (normal / abnormal). Route, curve, lane change start times, cruise speed and speed limit are shared.
 * The speed profiles differ only between lane changes, so the speed during every lane change is the same
 * and the steering score cannot depend on the speed variant. Likewise the speed profile does not depend on
 * the steering variant, so every baseline scores ①=② and ③=④.
 */
object Scenarios {

    const val SAMPLE_INTERVAL_MS = 100L
    const val SPEED_LIMIT_KMH = 80f
    const val CRUISE_KMH = 75.0

    // Scenario routes are not real roads. Each family gets its own start near Pangyo so the routes sit side by
    // side on the cumulative map, and repeated replays of a route put their events on the same spots.
    val DEFAULT_START = GeoStart(GeoPoint(37.4020, 127.1080), headingDeg = 70.0)
    private val MATRIX_START = GeoStart(GeoPoint(37.3950, 127.0950), headingDeg = 60.0)
    private val MIXED_START = GeoStart(GeoPoint(37.3890, 127.1010), headingDeg = 95.0)
    private val MANEUVERS_START = GeoStart(GeoPoint(37.4060, 127.0855), headingDeg = 10.0)

    /** Fixed camera at 52 s and an average-speed section from 112 s to 136 s. */
    val MATRIX_ZONES = listOf(
        EnforcementZone(ZoneKind.CAMERA, "과속단속 카메라", startMs = 52_000),
        EnforcementZone(ZoneKind.SECTION, "구간단속", startMs = 112_000, endMs = 136_000),
    )

    val NORMAL = matrix("nn", "① 속도 정상 · 조향 정상", "제한 80 · 75km/h 순항 · 깜빡이 켜고 부드럽게 차선변경",
        speedAbnormal = false, steerAbnormal = false)
    val STEER_ONLY = matrix("ns", "② 속도 정상 · 조향 비정상", "①과 속도 같음 · 깜빡이 없는 급차선변경, 칼치기",
        speedAbnormal = false, steerAbnormal = true)
    val SPEED_ONLY = matrix("sn", "③ 속도 비정상 · 조향 정상", "①과 조향 같음 · 급출발, 과속, 카메라 앞 급감속",
        speedAbnormal = true, steerAbnormal = false)
    val BOTH = matrix("ss", "④ 속도 비정상 · 조향 비정상", "③의 속도 + ②의 조향",
        speedAbnormal = true, steerAbnormal = true)

    val MIXED = Scenario(
        id = "mixed",
        title = "⑤ 기능 시연 · 야간",
        description = "정상·급·무깜빡이 차선변경, 커브 구분, 야간 가중치",
        start = MIXED_START,
    ) { mixed() }

    val MANEUVERS = Scenario(
        id = "maneuvers",
        title = "⑥ 구분 시연 · 회전·커브·차선변경",
        description = "우회전·좌회전·유턴은 회전으로, 커브는 커브로, 커브 위 차선변경은 차선변경으로 구분",
        start = MANEUVERS_START,
    ) { maneuvers() }

    val matrix = listOf(NORMAL, STEER_ONLY, SPEED_ONLY, BOTH)
    val all = matrix + MIXED + MANEUVERS

    /**
     * ⑦ A real road: 시흥동(판교) → 판교IC → 경부고속도로 → 양재IC → 양재, 11.5 km, GPS on every sample. Geometry from
     * OpenStreetMap (© OpenStreetMap contributors, ODbL) offline; steering follows the road
     * shape. Three drives of the same route (risky, medium, good) put repeated deductions on the same real spots.
     */
    private val ROUTE_START = GeoStart(GeoPoint(37.4127, 127.0947), headingDeg = 100.0)
    private val ROUTE_TITLES = listOf("⑦ 실제 도로 · 판교IC → 경부고속도로 → 양재IC", "⑦ 실제 도로 (2회차)", "⑦ 실제 도로 (3회차)")

    fun routeTrip(n: Int) = Scenario(
        id = "route$n",
        title = ROUTE_TITLES[n - 1],
        description = "OpenStreetMap 실제 도로 모양 · 고속도로 진입·진출, 깜빡이 없는 급차선변경, 과속, 급감속",
        start = ROUTE_START,
    ) { resource("/scenarios/route_$n.csv") }

    /**
     * Drives along the navigation's routes to other frequent destinations (generated offline, the 시연 모드
     * simulated drives): real roads in other places, for the cumulative map.
     */
    val OTHER_ROADS: List<Scenario> = listOf(
        Triple("live-pangyo", 1, "판교역 ② 분당내곡"), Triple("live-yangjae", 0, "양재역 ① 추천"),
        Triple("live-gangnam", 0, "강남역 ① 추천"), Triple("live-suwon", 0, "수원 영통 ① 추천"),
        Triple("live-yangjae", 2, "양재역 ③ 경부고속도로"),
    ).map { (od, i, title) ->
        Scenario(id = "live:${od}_$i", title = "실제 도로 · $title", description = "내비 경로를 따라 달린 주행", start = ROUTE_START) {
            java.util.zip.GZIPInputStream(Scenarios::class.java.getResourceAsStream("/scenarios/live/${od}_$i.csv.gz")!!)
                .bufferedReader(Charsets.UTF_8).useLines { SampleCsv.parse(it) }
        }
    }

    val ROUTE = routeTrip(1)
    val ROUTE_TRIPS = (1..3).map(::routeTrip)

    private fun resource(path: String): List<DrivingSample> =
        Scenarios::class.java.getResourceAsStream(path)!!.bufferedReader(Charsets.UTF_8).useLines { SampleCsv.parse(it) }

    private fun matrix(id: String, title: String, description: String, speedAbnormal: Boolean, steerAbnormal: Boolean) =
        Scenario(id, title, description, MATRIX_ZONES, MATRIX_START) {
            onMatrixRoad(matrixRoute(speedAbnormal, steerAbnormal), matrixRoute(speedAbnormal, steerAbnormal, lanes = false))
        }

    /**
     * The one road ①–④ drive on, as (distance from the start in metres, east, north, compass heading): the
     * centre line of ①'s speed profile and curve without its lane changes, dead-reckoned from [MATRIX_START].
     */
    private val MATRIX_ROAD: List<DoubleArray> by lazy {
        deadReckon(matrixRoute(speedAbnormal = false, steerAbnormal = false, lanes = false))
    }

    /** (distance, east, north, heading) per sample by the same bicycle model the analyzer uses. */
    private fun deadReckon(samples: List<DrivingSample>): List<DoubleArray> {
        val cfg = AnalyzerConfig()
        var h = MATRIX_START.headingDeg
        var x = 0.0
        var y = 0.0
        var d = 0.0
        var prev: DrivingSample? = null
        return samples.map { s ->
            prev?.let { p ->
                val dt = (s.timeMs - p.timeMs) / 1000.0
                h += yawRateDegS(s.speedKmh, s.steeringDeg, cfg) * dt
                val step = s.speedKmh / 3.6 * dt
                x += step * sin(Math.toRadians(h))
                y += step * cos(Math.toRadians(h))
                d += step
            }
            prev = s
            doubleArrayOf(d, x, y, h)
        }
    }

    /**
     * Puts a matrix drive on [MATRIX_ROAD] with a position on every sample, so all four variants and every replay
     * draw the same road and the curve sits in one place on the map. Dead reckoning alone would bend the faster
     * variants' road where they happen to be at 78 s, a few hundred metres further on, and draw ③④ as a second,
     * parallel road. The position is the road point at the distance driven, moved sideways by how far the drive's
     * lane changes took it from its own lane-less path ([plain], same speeds and curve), so lane changes still show.
     */
    private fun onMatrixRoad(samples: List<DrivingSample>, plain: List<DrivingSample>): List<DrivingSample> {
        val road = MATRIX_ROAD
        val drive = deadReckon(samples)
        val ref = deadReckon(plain)
        return samples.mapIndexed { i, s ->
            val p = drive[i]
            val r = ref[i]
            // Sideways offset (metres, right positive) from the lane-less path
            val hr = Math.toRadians(r[3])
            val side = (p[1] - r[1]) * cos(hr) - (p[2] - r[2]) * sin(hr)
            val (rx, ry, rh) = roadAt(road, p[0])
            val h = Math.toRadians(rh)
            s.copy(location = MATRIX_START.point.offset(rx + side * cos(h), ry - side * sin(h)))
        }
    }

    /** East, north and heading of [road] at [dist] metres, continuing straight past its end. */
    private fun roadAt(road: List<DoubleArray>, dist: Double): Triple<Double, Double, Double> {
        val i = road.indexOfFirst { it[0] >= dist }
        if (i == 0) return Triple(road[0][1], road[0][2], road[0][3])
        if (i < 0) {
            val last = road.last()
            val extra = dist - last[0]
            val h = Math.toRadians(last[3])
            return Triple(last[1] + extra * sin(h), last[2] + extra * cos(h), last[3])
        }
        val a = road[i - 1]
        val b = road[i]
        val f = if (b[0] > a[0]) (dist - a[0]) / (b[0] - a[0]) else 0.0
        return Triple(a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f, a[3] + (b[3] - a[3]) * f)
    }

    /**
     * 170 s at a posted 80 km/h. Lane changes start at 20, 70, 92 and 140 s and a left curve runs 78–88 s;
     * the speed is 75 km/h in both speed variants from 16 s to 150 s except in 34–66 s and 106–127 s,
     * which contain no steering.
     */
    private fun matrixRoute(speedAbnormal: Boolean, steerAbnormal: Boolean, lanes: Boolean = true): List<DrivingSample> =
        ScenarioBuilder(170.0).apply {
            val v = CRUISE_KMH
            if (!speedAbnormal) {
                speed(0.0 to 0.0, 16.0 to v, 150.0 to v, 170.0 to 0.0)
                brake(155.0, 170.0)
            } else {
                speed(
                    // Jackrabbit start
                    0.0 to 0.0, 3.0 to 40.0, 10.0 to v,
                    // Speeding toward the camera, braking hard just before it (52 s), flooring it right after
                    34.0 to v, 36.5 to 107.0, 48.0 to 107.0, 50.5 to 72.0, 53.0 to 72.0, 55.5 to 104.0,
                    57.0 to 104.0, 66.0 to v,
                    // Speeding inside the average-speed section, then braking hard
                    106.0 to v, 108.5 to 107.0, 124.0 to 107.0, 126.5 to v,
                    // Abrupt stop
                    158.0 to v, 160.5 to 35.0, 167.5 to 0.0, 170.0 to 0.0,
                )
                brake(48.0, 50.5)
                brake(124.0, 126.5)
                brake(158.0, 170.0)
            }

            curve(78.0, 2.0, 6.0, -8.0)
            if (!lanes) {
                // Lane-less reference path for placing the drive on the road (onMatrixRoad)
            } else if (!steerAbnormal) {
                laneChange(20.0, 5.0, -6.0); leftSignal(18.5, 25.5)
                laneChange(70.0, 5.0, 6.0); rightSignal(68.5, 75.5)
                // Two lanes to the left, one at a time
                laneChange(92.0, 5.0, -7.0); laneChange(98.0, 5.0, -7.0); leftSignal(90.5, 103.5)
                laneChange(140.0, 5.0, 6.0); rightSignal(138.5, 145.5)
            } else {
                laneChange(20.0, 2.2, -23.0)
                laneChange(70.0, 2.2, 23.0)
                // Weaving: the same two lanes back to back without straightening in between
                laneChange(92.0, 2.0, -32.0); laneChange(94.0, 2.0, -32.0)
                // Signaled, but still far too abrupt
                laneChange(140.0, 2.2, 19.0); rightSignal(138.5, 143.0)
            }
        }.build()

    /**
     * 150 s mixing city and highway: every steering maneuver the detector has to tell apart, with gentle
     * speed changes so no speed rule fires. Turns use large steering angles at walking-to-city speeds; the
     * highway curve carries one lane change toward its inside and one toward its outside.
     */
    private fun maneuvers(): List<DrivingSample> = ScenarioBuilder(150.0).apply {
        speed(
            0.0 to 0.0, 8.0 to 40.0, 13.0 to 20.0, 20.0 to 20.0, // slow down for the right turn
            30.0 to 60.0, 40.0 to 80.0, 88.0 to 80.0, // onto the highway
            98.0 to 20.0, 108.0 to 20.0, // off again for the left turn
            112.0 to 30.0, 118.0 to 15.0, 128.0 to 15.0, // U-turn
            138.0 to 40.0, 148.0 to 0.0, 150.0 to 0.0,
        )
        brake(8.0, 13.0); brake(88.0, 98.0); brake(112.0, 118.0); brake(138.0, 148.0)

        // Right turn at an intersection, signaled
        curve(14.5, 1.0, 1.7, 250.0); rightSignal(10.0, 18.0)
        // Lane change on the straight, signaled
        laneChange(45.0, 5.0, -6.0); leftSignal(43.5, 50.5)
        // Long left-hand curve
        curve(55.0, 2.0, 22.0, -6.0)
        // ... with a signaled lane change toward its inside
        laneChange(62.0, 5.0, -6.0); leftSignal(60.5, 67.5)
        // ... and an abrupt, unsignaled one toward its outside
        laneChange(72.0, 2.2, 20.0)
        // Left turn at an intersection, unsignaled
        curve(100.0, 1.0, 1.7, -250.0)
        // U-turn, signaled
        curve(120.0, 1.0, 2.7, -450.0); leftSignal(116.0, 125.0)
    }.build()

    private fun mixed(): List<DrivingSample> = ScenarioBuilder(117.0).apply {
        speed(
            0.0 to 0.0, 12.0 to 85.0, 26.0 to 85.0, 30.0 to 90.0, 37.0 to 90.0, 40.0 to 80.0, 52.0 to 80.0,
            55.0 to 45.0, 62.0 to 70.0, 84.0 to 70.0, 87.0 to 105.0, 90.0 to 120.0, 97.0 to 120.0, 117.0 to 0.0,
        )
        laneChange(20.0, 5.0, -5.0); leftSignal(18.5, 24.5)
        laneChange(34.0, 2.4, 14.0)
        curve(38.0, 2.0, 6.0, -8.0)
        brake(52.0, 55.0)
        duskFromS = 62.0
        laneChange(66.0, 2.4, -22.0); leftSignal(64.0, 68.5)
        laneChange(78.0, 5.0, 7.0)
        brake(100.0, 117.0)
    }.build()
}
