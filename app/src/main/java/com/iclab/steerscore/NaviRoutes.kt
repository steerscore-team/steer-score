package com.iclab.steerscore

import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteOverviewShowInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.iclab.steerscore.core.RouteProfile
import com.iclab.steerscore.core.RouteStep
import com.iclab.steerscore.core.Situation

/**
 * The navigation's own candidate routes, one per RouteOption (NaviHelper): during route guidance it reads the
 * destination (getDestinationInfo: poiId, position), searches the route to it once per RouteOption
 * (requestRoute with RouteInfo.routeOption), waits for the new turn-by-turn list and the remaining distance / time /
 * toll, and turns the list into a [RouteProfile] (expressway km between IC entry and exit, number of IC entries,
 * merges, exits, forks, intersection turns, roundabouts, tolls). At the end it guides with the driver's option again,
 * or with the one they pick ([select]). changeRouteOption() is the documented way to switch during guidance, but the
 * emulator's navigation took the request without searching again (log: ChangeRouteOptionRequest, no callback).
 *
 * The routes doc: "Request route option changes and rerouting only when an active route exists", results in the
 * NaviHelperEventListener callbacks; LiveVehicleSource forwards those here.
 */
class NaviRoutes(
    private val navi: () -> NaviHelper?,
    /**
     * requestRoute() brings the navigation to the front; an app left in the background is frozen by Android within
     * seconds and stops getting callbacks (seen on the emulator), so the app comes back to the front after each search.
     */
    private val bringToFront: () -> Unit = {},
    /** The car is (nearly) standing still: comparing all options re-routes the navigation 8 times. */
    private val stopped: () -> Boolean = { true },
) {

    private val handler = Handler(Looper.getMainLooper())
    @Volatile var currentOption: RouteOption? = null
        private set
    @Volatile var routing = false
        private set
    @Volatile private var tbt: List<TBTInfo> = emptyList()
    @Volatile private var tbtAtMs = 0L

    private var queue = ArrayDeque<RouteOption>()
    private var waitingFor: RouteOption? = null
    private var optionChangedAtMs = 0L
    private var results = mutableListOf<Pair<RouteOption, RouteProfile>>()
    private var original: RouteOption? = null
    private var onDone: ((List<RouteProfile>, String?) -> Unit)? = null
    private var onProgress: ((String) -> Unit)? = null
    private var destination: DestinationInfo? = null
    private var destinationAtMs = 0L
    /** Where the guided route goes: the poiId and position every per-option search uses. */
    private var target: DestinationInfo? = null
    @Volatile private var routeStartedAtMs = 0L
    /** Distance, duration and toll of the route the navigation just started (RouteStartInfo). */
    @Volatile private var startInfo: RouteStartInfo? = null
    val busy: Boolean get() = onDone != null

    // ---- Forwarded NaviHelper callbacks (binder threads) ----
    fun onTBTInfo(list: List<TBTInfo>) {
        tbt = list
        tbtAtMs = now()
    }

    fun onRouteStateInfo(info: RouteStateInfo) {
        routing = info.isRouting
        currentOption = info.routeOption
    }

    fun onRouteOptionChanged(option: RouteOption) {
        currentOption = option
        handler.post { if (option == waitingFor) optionChangedAtMs = now() }
    }

    /** The navigation's recent destinations, for the destination history (getRecentDestinationInfo). */
    var onRecent: ((List<com.iclab.steerscore.core.Place>) -> Unit)? = null

    fun requestRecent() {
        runCatching { navi()?.getRecentDestinationInfo() }.onFailure { Log.w(TAG, "recent destinations: ${it.message}") }
    }

    fun onRecentDestinationInfo(list: List<ai.pleos.playground.navi.data.RecentDestinationInfo>) {
        val places = list.map {
            com.iclab.steerscore.core.Place(it.name, it.latitude, it.longitude, it.address, it.poiId, it.poiSubId ?: "", it.poiProvider ?: "")
        }
        handler.post { onRecent?.invoke(places) }
    }

    /** Guides to [p] (a destination from the history): the navigation shows its route choice and starts guiding. */
    fun routeTo(p: com.iclab.steerscore.core.Place): Boolean = runCatching {
        val n = navi() ?: return false
        afterClearingWaypoints(n) {
            n.requestRoute(RouteInfo(p.lat, p.lon, p.lat, p.lon, p.name, p.poiId, p.address, p.poiSubId, RouteOption.RECOMMENDED, p.poiProvider, false))
        }
        chosenAlt = null
        true
    }.getOrElse { Log.w(TAG, "route to ${p.name}: ${it.message}"); false }

    fun onDestinationInfo(info: DestinationInfo) {
        handler.post {
            destination = info
            destinationAtMs = now()
        }
    }

    fun onRouteStarted(info: RouteStartInfo) {
        routing = true
        startInfo = info
        routeStartedAtMs = now()
    }

    fun onRouteEnded() {
        routing = false
        tbt = emptyList()
    }

    /** Asks the navigation whether a route is being guided (onRouteStateInfo answers). */
    fun refresh() {
        runCatching { navi()?.getRouteStateInfo() }.onFailure { Log.w(TAG, "route state: ${it.message}") }
    }

    /**
     * The route being guided: its profile for the prediction, where it goes (name and position), where the car is
     * (the navigation's own position, when it has sent one) and its turn-by-turn points in order.
     */
    data class Guided(
        val profile: RouteProfile,
        val destination: String,
        val steps: List<RouteStep>,
        val to: com.iclab.steerscore.core.GeoPoint,
        val from: com.iclab.steerscore.core.GeoPoint?,
    )

    /** The navigation's current position (onCurrentLocationInfo), for the start pin of the live route map. */
    @Volatile var here: com.iclab.steerscore.core.GeoPoint? = null
        private set

    fun onCurrentLocation(lat: Double, lon: Double) {
        if (lat != 0.0 && lon != 0.0) here = com.iclab.steerscore.core.GeoPoint(lat, lon)
    }

    /**
     * The route being guided now, without searching again (so the screen stays on this app): remaining distance, time
     * and toll from getDestinationInfo() and the whole turn-by-turn list from getTBTInfo().
     */
    fun current(onDone: (Guided?, String?) -> Unit) {
        if (busy) return
        val n = navi() ?: run {
            onDone(null, "내비에 연결되지 않았어요.")
            return
        }
        val startedAt = now()
        runCatching { n.getDestinationInfo() }
        runCatching { n.getTBTInfo() }
        var gotInfoAt = 0L
        fun waitInfo() {
            handler.postDelayed({
                val d = destination?.takeIf { destinationAtMs >= startedAt }
                if (d != null && gotInfoAt == 0L) gotInfoAt = now()
                // With the destination in hand, give the turn-by-turn list a moment to arrive as well
                val tbtReady = tbtAtMs >= startedAt || (gotInfoAt > 0 && now() - gotInfoAt > TBT_WAIT_MS)
                when {
                    // A destination guided by coordinates (a history entry without a POI) has no poiId: still guidance
                    d != null && d.remainDistance > 0 && tbtReady -> {
                        target = d
                        val opt = currentOption ?: RouteOption.RECOMMENDED
                        val list = tbt.takeIf { it.isNotEmpty() }
                        // getDestinationInfo() gave toll 0 on a route the navigation itself quoted 1,330 won (emulator):
                        // take the toll from the route start when this app saw it, else call it unknown
                        val toll = d.tollFare.takeIf { it > 0 } ?: startInfo?.tollFare?.takeIf { routeStartedAtMs > 0 } ?: -1
                        Log.i(TAG, "current route $opt to ${d.name}: ${d.remainDistance} m / ${d.remainTime} / toll ${d.tollFare} -> $toll; tbt " +
                            (list?.joinToString { "${it.type.name}@${it.distance.toInt()} '${it.description}'" } ?: "none"))
                        val profile = profileOf(opt, list, d.remainDistance.toDouble(), d.remainTime, toll)
                        onDone(Guided(profile, d.name, list?.let(::stepsOf).orEmpty(), com.iclab.steerscore.core.GeoPoint(d.latitude, d.longitude), here), null)
                    }
                    now() - startedAt > 5_000 -> onDone(null, NOT_GUIDING)
                    else -> waitInfo()
                }
            }, POLL_MS)
        }
        waitInfo()
    }

    /**
     * Compares the routes of all RouteOptions on the guided route. [onDone] gets the distinct routes (options that
     * give the same route are merged) and an error text when there is no route guidance.
     */
    fun compare(onProgress: (String) -> Unit, onDone: (List<RouteProfile>, String?) -> Unit) {
        if (busy) return
        val n = navi() ?: run {
            onDone(emptyList(), "내비에 연결되지 않았어요.")
            return
        }
        if (!stopped()) {
            onDone(emptyList(), "다른 옵션 비교는 정차 중에만 할 수 있어요. 내비가 경로를 여러 번 다시 찾기 때문이에요.")
            return
        }
        this.onDone = onDone
        this.onProgress = onProgress
        original = currentOption ?: RouteOption.RECOMMENDED
        results = mutableListOf()
        queue = ArrayDeque(RouteOption.entries)
        destination = null
        onProgress("내비 목적지 확인 중…")
        runCatching { n.getDestinationInfo() }
        val startedAt = now()
        fun waitTarget() {
            handler.postDelayed({
                val d = destination
                when {
                    d != null && d.poiId.isNotBlank() -> { target = d; next() }
                    now() - startedAt > 5_000 -> {
                        this.onDone = null
                        onDone(emptyList(), NOT_GUIDING)
                    }
                    else -> waitTarget()
                }
            }, POLL_MS)
        }
        waitTarget()
    }

    /**
     * The app never sets waypoints. The navigation keeps a waypoint across new searches (one left from earlier tests
     * stayed on every route), so the first search of a session clears them (removeWaypoint(ALL)) and is sent after
     * that: removing a waypoint makes the navigation search its current route again, and that search arriving after
     * ours sent the car to the old destination (tapping 판교역 while guided to 양재역 went to 양재역).
     */
    private var waypointsCleared = false

    private fun afterClearingWaypoints(n: NaviHelper, request: () -> Unit) {
        if (waypointsCleared) {
            request()
            return
        }
        waypointsCleared = true
        runCatching { n.removeWaypoint(ai.pleos.playground.navi.constants.WaypointIndex.ALL) }
            .onFailure { Log.w(TAG, "remove waypoints: ${it.message}") }
        handler.postDelayed({ runCatching(request) }, 2_500)
    }

    private fun search(opt: RouteOption) {
        val t = target ?: return
        val n = navi() ?: return
        afterClearingWaypoints(n) {
            n.requestRoute(
                RouteInfo(t.latitude, t.longitude, t.displayLatitude, t.displayLongitude, t.name, t.poiId, t.address, t.poiSubId ?: "", opt, t.poiProvider ?: "", false),
            )
        }
    }

    /**
     * Shows the whole guided route on the navigation's own map for [durationMs] (NavigationScreenRequest.OverviewRoute),
     * then brings this app back.
     */
    fun overview(durationMs: Int = 6_000): Boolean = runCatching {
        val n = navi() ?: return false
        n.requestNavigationScreen(NavigationScreenRequest.OverviewRoute(RouteOverviewShowInfo(durationMs)))
        handler.postDelayed({ runCatching(bringToFront) }, durationMs + 700L)
        true
    }.getOrElse { Log.w(TAG, "route overview: ${it.message}"); false }

    /**
     * Brings up the navigation's own route choice for the guided destination: searching the route again shows its
     * candidate routes with a countdown (seen on the emulator), where the driver taps one. The app stays in the
     * background so the driver can pick. (RouteOptionList only showed the current route's turn list.)
     */
    fun openRouteChoice(): Boolean = runCatching {
        if (target == null) return false
        search(currentOption ?: RouteOption.RECOMMENDED)
        true
    }.getOrElse { Log.w(TAG, "route choice: ${it.message}"); false }


    /**
     * Guides to the same destination through [via] (requestRouteWithWaypoints, one waypoint): the SDK's only way to make
     * the navigation take one of its alternatives, which have no RouteOption. The app stays in the background so the
     * navigation's countdown runs out and it starts guiding (bringing the app back stopped it).
     */
    fun routeVia(via: com.iclab.steerscore.core.GeoPoint, name: String): Boolean = runCatching {
        val t = target ?: return false
        val n = navi() ?: return false
        waypointsCleared = true
        val dest = RouteInfo(t.latitude, t.longitude, t.displayLatitude, t.displayLongitude, t.name, t.poiId, t.address,
            t.poiSubId ?: "", currentOption ?: RouteOption.RECOMMENDED, t.poiProvider ?: "", false)
        val wp = ai.pleos.playground.navi.data.RequestWaypointInfo(via.lat, via.lon, name,
            ai.pleos.playground.navi.constants.WaypointIndex.FIRST, "", "", "", "")
        n.requestRouteWithWaypoints(ai.pleos.playground.navi.data.RouteWithWaypointsRequest(dest, listOf(wp)))
        Log.i(TAG, "route via $name (${via.lat}, ${via.lon})")
        true
    }.getOrElse { Log.w(TAG, "route via: ${it.message}"); false }

    /** Back to the navigation's own route: the waypoint is removed (the navigation searches its route again). */
    fun dropWaypoint(): Boolean = runCatching {
        val n = navi() ?: return false
        n.removeWaypoint(ai.pleos.playground.navi.constants.WaypointIndex.ALL)
        chosenAlt = null
        true
    }.getOrElse { Log.w(TAG, "drop waypoint: ${it.message}"); false }

    /** Which of the navigation's demo routes the guidance follows through a waypoint ([routeVia]); null for its own. */
    @Volatile var chosenAlt: Int? = null

    /** Switches the guidance to [option] (the route the driver picked). */
    fun select(option: String): Boolean = runCatching {
        val o = RouteOption.valueOf(option)
        chosenAlt = null  // a plain search drops the waypoint
        if (target != null) search(o) else navi()?.changeRouteOption(o)
        currentOption = o
        true
    }.getOrElse { Log.w(TAG, "change route option: ${it.message}"); false }

    private fun next() {
        val opt = queue.removeFirstOrNull()
        if (opt == null) {
            finish()
            return
        }
        waitingFor = opt
        destination = null
        optionChangedAtMs = 0L
        onProgress?.invoke("경로 비교 중 · ${OPTION_KO[opt] ?: opt.name} (${RouteOption.entries.size - queue.size}/${RouteOption.entries.size})")
        val startedAt = now()
        runCatching { search(opt) }.onFailure {
            Log.w(TAG, "requestRoute $opt: ${it.message}")
            handler.post { next() }
            return
        }
        handler.postDelayed({ runCatching(bringToFront) }, FRONT_DELAY_MS)
        poll(opt, startedAt)
    }

    /**
     * Waits for the navigation to start the searched route (onRouteStarted with its distance, duration and toll),
     * then briefly for its turn-by-turn list, which says what the route goes through.
     */
    private fun poll(opt: RouteOption, startedAt: Long) {
        handler.postDelayed({
            val t = now()
            when {
                t - startedAt > STEP_TIMEOUT_MS -> {
                    Log.w(TAG, "route option $opt: no answer")
                    next()
                }
                // The navigation did not report the start (seen on the emulator from the 2nd search on): once its
                // turn-by-turn list is new, read the remaining distance / time / toll with getDestinationInfo()
                routeStartedAtMs < startedAt -> {
                    val d = destination?.takeIf { destinationAtMs > startedAt && it.remainDistance > 0 }
                    if (d != null && tbtAtMs > startedAt) {
                        Log.i(TAG, "route $opt (destination info): ${d.remainDistance} m / ${d.remainTime} / toll ${d.tollFare}; tbt " +
                            tbt.joinToString { "${it.type.name}@${it.distance.toInt()}" })
                        results += opt to profileOf(opt, tbt, d.remainDistance.toDouble(), d.remainTime, d.tollFare)
                        next()
                    } else {
                        if (t - startedAt > 6_000 && (t / POLL_MS) % 8 == 0L) runCatching { navi()?.getDestinationInfo() }
                        poll(opt, startedAt)
                    }
                }
                tbtAtMs < routeStartedAtMs && t - routeStartedAtMs < TBT_WAIT_MS -> poll(opt, startedAt)
                else -> {
                    val info = startInfo!!
                    val list = tbt.takeIf { tbtAtMs >= routeStartedAtMs }
                    Log.i(TAG, "route $opt: ${info.name} ${info.distance} m / ${info.duration} / toll ${info.tollFare}; tbt " +
                        (list?.joinToString { "${it.type.name}@${it.distance.toInt()}" } ?: "none"))
                    results += opt to profileOf(opt, list, info.distance.toDouble(), info.duration, info.tollFare)
                    next()
                }
            }
        }, POLL_MS)
    }

    private fun finish() {
        val done = onDone ?: return
        onDone = null
        waitingFor = null
        // Back to the driver's option; selecting another route later switches again
        original?.let { o -> runCatching { search(o) } }
        handler.postDelayed({ runCatching(bringToFront) }, FRONT_DELAY_MS)
        // Options that gave the same route become one candidate
        val merged = results.groupBy { (_, p) -> Triple(Math.round(p.distanceKm * 10), p.timeMin, p.counts) }
            .map { (_, same) -> same.first().second.copy(options = same.map { it.first.name }) }
        done(merged, if (merged.isEmpty()) "내비가 경로 옵션 변경에 응답하지 않았어요." else null)
    }

    companion object {
        private const val TAG = "SteerScore"
        private const val POLL_MS = 250L
        private const val STEP_TIMEOUT_MS = 20_000L
        private const val TBT_WAIT_MS = 2_500L
        private const val FRONT_DELAY_MS = 1_500L
        private const val NOT_GUIDING = "내비 길안내 중이 아니에요. 내비에서 목적지를 찍고 길안내를 시작하세요."
        private fun now() = android.os.SystemClock.elapsedRealtime()

        val OPTION_KO = mapOf(
            RouteOption.RECOMMENDED to "추천", RouteOption.SHORT_TIME to "최소시간", RouteOption.SHORT_DISTANCE to "최단거리",
            RouteOption.HIGHWAY to "고속도로 우선", RouteOption.FREE to "무료도로", RouteOption.COMFORTABLE to "편한길",
            RouteOption.SCHOOL_ZONE to "어린이보호구역 회피",
        )

        fun situationOf(t: TurnType): Situation? = when (t.name) {
            "TURN_ENTER_IC_R", "TURN_ENTER_IC_L", "TURN_ENTER_IC_C" -> Situation.IC_ENTRY
            "TURN_EXIT_IC_R", "TURN_EXIT_IC_L", "TURN_EXIT_IC_C" -> Situation.IC_EXIT
            "TURN_DEVIDE_MERGE" -> Situation.MERGE
            "TURN_KEEP_R", "TURN_KEEP_L" -> Situation.FORK
            "TURN_TOLL" -> Situation.TOLL
            "TURN_LEFT", "TURN_RIGHT", "TURN_UTURN", "TURN_SHARP_L", "TURN_SHARP_R" -> Situation.INTERSECTION
            else -> if (t.name.contains("ROUNDABOUT")) Situation.ROUNDABOUT else null
        }

        /**
         * A route from its turn-by-turn list: the distances are taken as from the car to each point when they never
         * decrease, else as the length of each step. Every IC entry is also a merge onto the expressway.
         */
        /** How far along the route each turn-by-turn point is, in metres. */
        fun positionsOf(list: List<TBTInfo>): List<Double> {
            val raw = list.map { it.distance }
            val cumulative = raw.zipWithNext().all { (a, b) -> b >= a }
            return if (cumulative) raw else raw.runningReduce { acc, x -> acc + x }
        }

        fun stepsOf(list: List<TBTInfo>): List<RouteStep> =
            positionsOf(list).zip(list) { at, t -> RouteStep(at, situationOf(t.type), turnLabel(t.type), t.description) }

        /** The manoeuvre in words, for the route's turn list. */
        fun turnLabel(t: TurnType): String {
            val n = t.name
            return when {
                n.startsWith("TURN_ENTER_IC") -> "IC 진입"
                n.startsWith("TURN_EXIT_IC") -> "IC 진출"
                n == "TURN_TOLL" -> "톨게이트"
                n == "TURN_DEVIDE_MERGE" -> "합류"
                n == "TURN_KEEP_R" -> "오른쪽 분기"
                n == "TURN_KEEP_L" -> "왼쪽 분기"
                n == "TURN_LEFT" || n == "TURN_SHARP_L" -> "좌회전"
                n == "TURN_RIGHT" || n == "TURN_SHARP_R" -> "우회전"
                n == "TURN_UTURN" -> "유턴"
                n.startsWith("TURN_ENTER_OVER") -> "고가도로 진입"
                n.startsWith("TURN_ENTER_UNDER") -> "지하차도 진입"
                n.startsWith("TURN_AVOID_UNDER") || n.startsWith("TURN_AVOID_OVER") -> "옆길"
                n == "TURN_ARRIVE" -> "도착"
                n.contains("ROUNDABOUT") -> "회전교차로"
                else -> n.removePrefix("TURN_").lowercase()
            }
        }

        fun profileOf(opt: RouteOption, list: List<TBTInfo>?, distanceM: Double, durationS: Int, tollWon: Int): RouteProfile {
            if (list.isNullOrEmpty()) {
                // No turn-by-turn list (the emulator's navigation sends none while parked): what the route goes through
                // is unknown, the advisor falls back to a per-km estimate
                return RouteProfile(listOf(opt.name), distanceM / 1000.0, Math.round(durationS / 60.0).toInt(), tollWon, 0.0, emptyMap(), composition = false)
            }
            val at = positionsOf(list)
            val sits = list.map { situationOf(it.type) }
            var expressway = 0.0
            var enteredAt: Double? = if (sits.firstOrNull { it == Situation.IC_ENTRY || it == Situation.IC_EXIT } == Situation.IC_EXIT) 0.0 else null
            sits.forEachIndexed { i, s ->
                if (s == Situation.IC_ENTRY && enteredAt == null) enteredAt = at[i]
                if (s == Situation.IC_EXIT) enteredAt?.let { expressway += at[i] - it; enteredAt = null }
            }
            enteredAt?.let { expressway += (distanceM - it).coerceAtLeast(0.0) }
            val counts = sits.filterNotNull().groupingBy { it }.eachCount().toMutableMap()
            counts[Situation.MERGE] = (counts[Situation.MERGE] ?: 0) + (counts[Situation.IC_ENTRY] ?: 0)
            return RouteProfile(
                options = listOf(opt.name),
                distanceKm = distanceM / 1000.0,
                timeMin = Math.round(durationS / 60.0).toInt(),
                tollWon = tollWon,
                expresswayKm = (expressway / 1000.0).coerceAtMost(distanceM / 1000.0),
                counts = counts,
            )
        }
    }
}
