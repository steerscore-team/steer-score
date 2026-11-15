package com.iclab.steerscore

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.iclab.steerscore.core.Exposure
import com.iclab.steerscore.core.OdPair
import com.iclab.steerscore.core.Persona
import com.iclab.steerscore.core.RouteAdvisor
import com.iclab.steerscore.core.RouteDemo
import com.iclab.steerscore.core.RouteProfile
import com.iclab.steerscore.core.Situation
import java.util.Locale

/**
 * The route recommendation screen. Left: whose driving history to predict with (3 virtual personas) and the origin →
 * destination pairs of the demo. Right: the routes the navigation offers for the pair (one per RouteOption, the same
 * route merged) with time, distance, toll, what the route goes through, and the predicted SteerScore with its largest
 * expected deductions. Tapping a route opens a comparison under the list (the route now against the tapped one:
 * predicted score, time, distance, toll, what each goes through, largest expected deductions) with a confirm button;
 * only confirming switches the navigation to that RouteOption during route guidance ([NaviRoutes]). The demo pairs
 * only mark the choice.
 */
class RoutePlanner(private val a: Activity, private val naviRoutes: () -> NaviRoutes?) {

    private val overlay: View = a.findViewById(R.id.routeOverlay)
    private val subtitle: TextView = a.findViewById(R.id.routeSubtitle)
    private val personasView: LinearLayout = a.findViewById(R.id.routePersonas)
    private val odsView: LinearLayout = a.findViewById(R.id.routeOds)
    private val header: TextView = a.findViewById(R.id.routeHeader)
    private val basis: TextView = a.findViewById(R.id.routeBasis)
    private val cards: LinearLayout = a.findViewById(R.id.routeCards)
    private val status: TextView = a.findViewById(R.id.routeStatus)
    private val confirmView: LinearLayout = a.findViewById(R.id.routeConfirm)
    private val mapView: com.iclab.steerscore.ui.RouteMapView = a.findViewById(R.id.routeMap)
    private val store = DestinationStore(a)
    /** Destinations of the driver's guided drives (and the navigation's recent ones), most driven first. */
    private val history = store.load().also { h ->
        // Demo: a few usual destinations from the emulator's position (판교 시흥동), as if driven before
        if (!store.seeded) {
            val day = 86_400_000L
            val now = System.currentTimeMillis()
            h.addSeed(listOf(
                com.iclab.steerscore.core.Place("판교역", 37.3947, 127.1112, count = 9, lastAt = now - day),
                com.iclab.steerscore.core.Place("양재역", 37.4841, 127.0346, count = 6, lastAt = now - 2 * day),
                com.iclab.steerscore.core.Place("강남역", 37.4979, 127.0276, count = 4, lastAt = now - 3 * day),
                com.iclab.steerscore.core.Place("수원 영통", 37.2512, 127.0714, count = 2, lastAt = now - 6 * day),
                com.iclab.steerscore.core.Place("인천국제공항 제1여객터미널", 37.4490, 126.4510, count = 1, lastAt = now - 12 * day),
            ))
            store.save(h)
            store.seeded = true
        }
    }

    private val demo: RouteDemo by lazy { RouteAdvisor.loadDemo() }
    private var persona: Persona? = null

    /** A driver without 시연 모드 gets no persona choice: the prediction uses every recorded drive (a standard driver). */
    var demoMode = false
        set(v) {
            field = v
            a.findViewById<View>(R.id.routeDriverTitle).visibility = if (v) View.VISIBLE else View.GONE
            personasView.visibility = if (v) View.VISIBLE else View.GONE
            persona = if (v) demo.personas.first() else standard
            if (visible) render()
        }
    private val standard by lazy { Persona("standard", "표준 운전자", "", demo.allTrips) }
    private var od: OdPair? = null
    /** Routes from the navigation (the guided route, or all options after a comparison) instead of a demo pair. */
    private var liveRoutes: List<RouteProfile>? = null
    /** [liveRoutes] holds every option's route, not only the guided one. */
    private var liveCompared = false
    /** The route being guided, with where it goes and what it passes, for the strip under its card. */
    private var guided: NaviRoutes.Guided? = null
    /**
     * Guiding to the emulator demo destination: [liveRoutes] is the guided route followed by the navigation's other two
     * routes (bundled), drawn on [liveAltMap].
     */
    private var liveAltMap: com.iclab.steerscore.core.RouteMapData? = null
    private var liveDemo: com.iclab.steerscore.core.RouteMaps.LiveDemo? = null
    /** Which of [liveRoutes] the navigation is guiding along. */
    private var guidedIndex = 0
    private val guidedRoute: RouteProfile? get() = liveRoutes?.getOrNull(guidedIndex)
    private var picked: RouteProfile? = null
    /** The route tapped and shown in the comparison, waiting for the driver to confirm. */
    private var pending: RouteProfile? = null

    val visible: Boolean get() = overlay.visibility == View.VISIBLE

    init {
        a.findViewById<View>(R.id.routeClose).setOnClickListener { overlay.visibility = View.GONE }
        // Tapping a route on the map is the same as tapping its card
        mapView.onRouteTap = { i -> (liveRoutes ?: od?.routes)?.getOrNull(i)?.let { pick(it) } }
    }

    private var live = false
    private var emptyRetries = 0

    /** Back in the app (from the navigation): the guidance may have changed meanwhile, so read it again. */
    fun refresh() {
        if (visible && live) loadCurrent()
    }

    fun show(live: Boolean) {
        this.live = live
        if (persona == null) persona = if (demoMode) demo.personas.first() else standard
        subtitle.text = if (demoMode) "경로별 예상 운전점수 · 운전자 기록은 데모용" else "경로별 예상 운전점수"
        status.text = ""
        overlay.visibility = View.VISIBLE
        render()
        if (live) {
            // The navigation's own recent destinations seed the history
            naviRoutes()?.let { nr ->
                nr.onRecent = { places ->
                    history.addRecent(places, System.currentTimeMillis())
                    store.save(history)
                    renderOds()
                }
                nr.requestRecent()
            }
            loadCurrent()
        }
    }

    /** During route guidance: the predicted score of the route being guided, without touching the navigation. */
    private fun loadCurrent() {
        val nr = naviRoutes() ?: return
        // Still waiting for the previous request (the navigation is searching): ask again shortly
        if (nr.busy) {
            overlay.postDelayed({ if (visible) loadCurrent() }, 1_500)
            return
        }
        status.text = "지금 길안내 중인 경로를 확인하는 중…"
        nr.current { g, error ->
            a.runOnUiThread {
                if (g == null) {
                    status.text = error ?: ""
                    // The navigation may still be counting down to start guiding: look again a few times
                    if (visible && emptyRetries++ < 3) overlay.postDelayed({ if (visible) loadCurrent() }, 4_000)
                    return@runOnUiThread
                }
                emptyRetries = 0
                guided = g
                // A guided drive to this destination: counted once per drive in the history
                if (history.record(com.iclab.steerscore.core.Place(g.destination, g.to.lat, g.to.lon), System.currentTimeMillis())) store.save(history)
                val demoTrip = com.iclab.steerscore.core.RouteMaps.liveDemoFor(g.to)
                // At the demo destination: the guided route first, then the navigation's other two routes
                // The navigation's remaining-route info gives no toll (0); its route screen showed one for the demo route
                // The guided one is the navigation's own route unless the driver switched to one of its alternatives
                // here (a waypoint on that road); it takes the guided route's real numbers and the demo route's name
                val demoRoutes = demoTrip?.od?.routes
                // The route switched to here, else the one whose places appear in the turn descriptions (the driver may
                // have picked it in the navigation itself)
                guidedIndex = naviRoutes()?.chosenAlt?.takeIf { demoRoutes != null && it in demoRoutes.indices }
                    ?: demoTrip?.guidedIndex(g.steps.map { it.description }) ?: 0
                liveRoutes = demoRoutes?.mapIndexed { i, r ->
                    if (i != guidedIndex) r
                    else g.profile.copy(options = r.options, tollWon = if (g.profile.tollWon < 0) r.tollWon else g.profile.tollWon)
                } ?: listOf(g.profile)
                liveAltMap = demoTrip?.map
                liveDemo = demoTrip
                liveCompared = false
                picked = null
                status.text = if (liveAltMap != null) "다른 경로를 누르면 지금 경로와 비교해요" else "지금 안내 중인 경로의 예상 점수예요"
                render()
            }
        }
    }

    fun hide() {
        overlay.visibility = View.GONE
    }

    private fun render() {
        pending = null
        renderPersonas()
        renderOds()
        renderRoutes()
    }

    private fun renderPersonas() {
        personasView.removeAllViews()
        demo.personas.forEach { p ->
            val on = p == persona
            personasView.addView(row(on) {
                persona = p
                picked = null
                status.text = ""
                render()
            }.apply {
                addView(text(p.name, 19f, bold = true))
                val avg = p.trips.map { it.score }.average()
                addView(text(p.summary.substringBefore(","), 15f, muted = true))
            })
        }
    }

    private fun renderOds() {
        odsView.removeAllViews()
        val guidedPlace = guided?.let { g -> com.iclab.steerscore.core.Place(g.destination, g.to.lat, g.to.lon) }
        val places = history.sorted()
        if (places.isEmpty()) odsView.addView(text("길안내로 다녀온 목적지가 여기에 쌓여요", 15f, muted = true).apply { setPadding(4, 4, 0, 12) })
        places.forEach { p ->
            val now = guidedPlace != null && p.sameAs(guidedPlace)
            val on = now && liveRoutes != null
            // One outlined row with the ★ inside it: a row nested in another layout had its top outline covered
            odsView.addView(row(on) {
                if (now) {
                    loadCurrent()
                } else {
                    status.text = if (naviRoutes()?.routeTo(p) == true) "내비가 '${p.name}'(으)로 경로를 찾아요. 카운트다운이 끝나면 안내를 시작해요"
                    else "실시간 모드에서 내비에 연결되어 있어야 길안내를 시작할 수 있어요"
                }
            }.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 10, 16, 10)
                // ★ pins a destination to the top
                addView(text(if (p.pinned) "★" else "☆", 26f).apply {
                    setTextColor(a.getColor(if (p.pinned) R.color.warn else R.color.text_muted))
                    setPadding(8, 0, 16, 0)
                    setOnClickListener {
                        history.togglePin(p)
                        store.save(history)
                        renderOds()
                    }
                })
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(p.name, 18f, bold = on))
                    val last = if (p.lastAt > 0) " · 최근 " + java.text.SimpleDateFormat("M/d", Locale.KOREA).format(java.util.Date(p.lastAt)) else ""
                    addView(text(if (now) "지금 길안내 중 · ${p.count}회" else "${p.count}회$last", 15f, muted = true))
                })
            })
        }
    }

    private fun renderRoutes() {
        val p = persona ?: return
        val routes = liveRoutes ?: od?.routes ?: run {
            header.text = "길안내 경로 비교"
            basis.text = "${p.name} 기록 ${demo.advisor(p).tripCount}회로 예상"
            cards.removeAllViews()
            cards.addView(text("자주 가는 목적지를 누르면 길안내를 시작해요. 길안내 중에는 내비가 제안한 경로들을 예상 점수로 비교해요.", 18f, muted = true).apply { setPadding(8, 12, 8, 0) })
            mapView.visibility = View.GONE
            return
        }
        val advisor = demo.advisor(p)
        val preds = advisor.predictAll(routes)
        header.text = liveRoutes?.let {
            val to = guided?.let { g -> " → ${g.destination}" } ?: ""
            if (liveCompared) "내비 길안내 경로$to · 옵션별 비교" else if (liveAltMap != null) "길안내 경로 비교$to" else "지금 길안내 중인 경로$to"
        } ?: od!!.let { "${it.from} → ${it.to}" }
        basis.text = "${p.name} 기록 ${advisor.tripCount}회로 예상"
        cards.removeAllViews()
        // The demo pairs have a map with their routes (bundled OpenStreetMap data). The navigation's own route has no
        // shape to draw: its map shows the roads around the car and the destination with the two pins
        val mapData = if (liveRoutes == null) od?.let { com.iclab.steerscore.core.RouteMaps.of(it.id) } else liveAltMap?.takeIf { !liveCompared }
        val showMap = mapData != null
        val indexed = liveRoutes ?: od!!.routes
        // The navigation's routes keep the navigation's order and numbers (① 추천 first), so they match its own route
        // screen; the best predicted score gets a badge instead. Demo pairs are ranked by predicted score.
        val navOrder = liveAltMap != null && liveRoutes != null && !liveCompared
        val sorted = if (navOrder) preds.sortedBy { indexed.indexOf(it.route) } else preds.sortedByDescending { it.score }
        val bestScore = preds.maxOf { it.score }
        val liveMap = if (showMap) null else guided?.takeIf { liveRoutes != null }?.let { g -> com.iclab.steerscore.core.RouteMaps.around(g.from, g.to) }
        mapView.visibility = if (showMap || liveMap != null) View.VISIBLE else View.GONE
        if (liveMap != null) {
            mapView.show(liveMap, emptyList(), "경로 선은 '내비 지도에서 경로 보기'로 볼 수 있어요")
        } else if (showMap) {
            // Live: the guided route is the highlighted one until another is tapped
            val highlight = pending ?: guidedRoute ?: picked ?: sorted.first().route
            mapView.show(mapData, sorted.mapIndexed { rank, pr ->
                com.iclab.steerscore.ui.RouteMapView.Candidate(
                    indexed.indexOf(pr.route), "${NUMBERS.getOrElse(rank) { "" }} ${pr.score}점 · ${pr.route.timeMin}분", pr.route == highlight,
                )
            }, if (liveRoutes != null) "경로 모양: OpenStreetMap에서 같은 도로로 그린 그림" else null)
        }
        sorted.forEachIndexed { rank, pr ->
            val r = pr.route
            val on = r == picked
            val tapped = r == pending
            cards.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, 16, 20, 16)
                background = GradientDrawable().apply {
                    cornerRadius = 14f
                    setColor(a.getColor(R.color.panel_line))
                    if (on || tapped) setStroke(4, a.getColor(if (tapped) R.color.warn else R.color.accent))
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 }
                isClickable = true
                setOnClickListener { pick(r) }
                // Predicted score
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(150, LinearLayout.LayoutParams.WRAP_CONTENT)
                    addView(text("${pr.score}", 46f, bold = true).apply {
                        gravity = Gravity.CENTER
                        setTextColor(a.getColor(scoreColor(pr.score)))
                    })
                    addView(text("예상 점수", 14f, muted = true).apply { gravity = Gravity.CENTER })
                })
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16, 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    // Badges only mean something when there is more than one route
                    val badges = if (preds.size < 2) emptyList() else listOfNotNull(
                        "안내 중".takeIf { liveAltMap != null && !liveCompared && r == guidedRoute },
                        "점수 최고".takeIf { navOrder && pr.score == bestScore },
                        "선택됨".takeIf { on },
                    )
                    val mark = if (showMap) NUMBERS.getOrElse(rank) { "" } + " " else ""
                    addView(text(mark + RouteAdvisor.optionLabel(r.options) + if (badges.isEmpty()) "" else "   [" + badges.joinToString(" · ") + "]", 20f, bold = true))
                    val toll = when {
                        r.tollWon > 0 -> "%,d원".format(Locale.US, r.tollWon)
                        r.tollWon < 0 -> "통행료 모름"
                        else -> "무료"
                    }
                    addView(text("${r.timeMin}분 · %.1f km · $toll".format(Locale.US, r.distanceKm), 17f))
                    val why = pr.top.firstOrNull()?.let { it.cause.label + if (demoMode) " -%.1f".format(Locale.US, it.points) else "" }
                    addView(text(if (why == null) "예상 감점 거의 없음" else "가장 큰 예상 감점: $why", 15f).apply {
                        setTextColor(a.getColor(R.color.warn))
                    })
                })
            })
        }
        if (liveAltMap == null) addGuidedStrip()
    }

    /** Under the guided route's card: the route as a strip, and a button to see it on the navigation's map. */
    private fun addGuidedStrip() {
        guided ?: return
        if (liveRoutes == null || liveCompared) return
        cards.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 10, 0, 0)
            addView(Button(a, null, 0, R.style.BarButton).apply {
                text = "내비 지도에서 경로 보기"
                gravity = Gravity.CENTER
                setOnClickListener {
                    status.text = if (naviRoutes()?.overview() == true) "내비가 경로 전체를 6초 동안 보여 주고 이 화면으로 돌아와요"
                    else "내비에 경로 보기를 요청하지 못했어요"
                }
            })
        })
    }

    /** Tapping a card only opens the comparison; [confirm] makes the change. */
    private fun pick(r: RouteProfile) {
        val label = RouteAdvisor.optionLabel(r.options)
        if (liveRoutes != null && !liveCompared && (liveAltMap == null || r == guidedRoute)) {
            status.text = "지금 이 경로('$label')로 안내 중이에요"
            return
        }
        pending = r
        status.text = ""
        renderRoutes()
        renderConfirm()
    }

    /** The route the comparison starts from: the one being guided (or already picked), else the navigation's default. */
    private fun baseRoute(routes: List<RouteProfile>): RouteProfile? {
        val current = if (liveRoutes != null) naviRoutes()?.currentOption?.name else null
        guidedRoute?.takeIf { liveAltMap != null && !liveCompared && it in routes }?.let { return it }
        return picked?.takeIf { it in routes }
            ?: current?.let { c -> routes.firstOrNull { c in it.options } }
            ?: routes.firstOrNull { "RECOMMENDED" in it.options }
            ?: routes.minByOrNull { it.timeMin }
    }

    /** "지금 경로 vs 고른 경로" table with 이 경로로 안내 / 취소, under the route list. */
    private fun renderConfirm() {
        confirmView.removeAllViews()
        val r = pending
        val p = persona
        val routes = liveRoutes ?: od?.routes
        if (r == null || p == null || routes == null) {
            confirmView.visibility = View.GONE
            return
        }
        val advisor = demo.advisor(p)
        val preds = advisor.predictAll(routes).associateBy { it.route }
        val base = baseRoute(routes)
        val now = base?.let { preds[it] }
        val new = preds.getValue(r)
        val live = liveRoutes != null
        val label = RouteAdvisor.optionLabel(r.options)
        confirmView.visibility = View.VISIBLE
        confirmView.background = GradientDrawable().apply {
            cornerRadius = 14f
            setColor(a.getColor(R.color.panel_line))
            setStroke(3, a.getColor(R.color.warn))
        }
        confirmView.setPadding(20, 14, 20, 14)
        val same = base == r
        confirmView.addView(text(if (same) "'$label' · 지금 이 경로예요" else "'$label' 경로로 바꿀까요?", 21f, bold = true))
        if (!same && now != null) {
            fun row(name: String, before: String, after: String, diff: String = "") = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 4)
                addView(text(name, 16f, muted = true).apply { layoutParams = LinearLayout.LayoutParams(170, LinearLayout.LayoutParams.WRAP_CONTENT) })
                addView(text(before, 17f).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                addView(text(after, 17f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                addView(text(diff, 16f).apply {
                    layoutParams = LinearLayout.LayoutParams(150, LinearLayout.LayoutParams.WRAP_CONTENT)
                    setTextColor(a.getColor(R.color.text_muted))
                })
            }
            val b = now.route
            fun toll(x: RouteProfile) = when {
                x.tollWon > 0 -> "%,d원".format(Locale.US, x.tollWon)
                x.tollWon < 0 -> "정보 없음"
                else -> "없음"
            }
            fun expressway(x: RouteProfile) = if (x.composition) "%.1f km".format(Locale.US, x.expresswayKm) else "모름"
            fun count(x: RouteProfile, vararg s: Situation) = if (x.composition) "${s.sumOf { x.counts[it] ?: 0 }}회" else "모름"
            fun top(x: RouteAdvisor.Prediction) =
                x.top.firstOrNull()?.let { it.cause.label + if (demoMode) " -%.1f".format(Locale.US, it.points) else "" } ?: "거의 없음"
            confirmView.addView(row("", "지금 · ${RouteAdvisor.optionLabel(b.options)}", "바꿀 경로 · $label").apply { setPadding(0, 10, 0, 2) })
            val ds = new.score - now.score
            confirmView.addView(row("예상 점수", "${now.score}점", "${new.score}점", if (ds == 0) "같음" else "%+d점".format(Locale.US, ds)))
            val dt = r.timeMin - b.timeMin
            confirmView.addView(row("시간", "${b.timeMin}분", "${r.timeMin}분", if (dt == 0) "같음" else "%+d분".format(Locale.US, dt)))
            confirmView.addView(row("거리", "%.1f km".format(Locale.US, b.distanceKm), "%.1f km".format(Locale.US, r.distanceKm),
                "%+.1f km".format(Locale.US, r.distanceKm - b.distanceKm)))
            confirmView.addView(row("통행료", toll(b), toll(r)))
            confirmView.addView(row("고속도로", expressway(b), expressway(r)))
            confirmView.addView(row("합류·IC", count(b, Situation.MERGE, Situation.IC_EXIT), count(r, Situation.MERGE, Situation.IC_EXIT)))
            confirmView.addView(row("교차로 회전", count(b, Situation.INTERSECTION), count(r, Situation.INTERSECTION)))
            confirmView.addView(row("예상 감점 1위", top(now), top(new)))
        }
        confirmView.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 12, 0, 0)
            if (!same) addView(Button(a, null, 0, R.style.BarButton).apply {
                text = if (live) "이 경로로 안내" else "이 경로 선택"
                gravity = Gravity.CENTER
                setTextColor(a.getColor(R.color.bg))
                setBackgroundColor(a.getColor(R.color.accent))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 12 }
                setOnClickListener { confirm(r) }
            })
            addView(Button(a, null, 0, R.style.BarButton).apply {
                text = if (same) "닫기" else "취소"
                gravity = Gravity.CENTER
                setOnClickListener {
                    pending = null
                    status.text = ""
                    renderRoutes()
                    renderConfirm()
                }
            })
        })
        if (!same && !live) confirmView.addView(text("데모 구간이라 내비 길안내는 바뀌지 않고 선택만 표시돼요", 15f, muted = true))
        if (!same && live && liveAltMap != null && !liveCompared)
            confirmView.addView(text("내비가 이 경로로 가도록 이 경로만 지나는 지점을 경유지로 넣어요. 내비 안내가 이 경로와 다르면 경유지를 지우고 내비 화면에서 고르게 해요", 15f, muted = true))
    }

    /**
     * After switching through a waypoint: the navigation's turn list should match the one captured for that route. If
     * it does not (the waypoint pulled it onto another way or a loop), drop the waypoint and open the navigation's route
     * screen so the driver picks it there.
     */
    private fun checkSwitched(index: Int, label: String, attempt: Int) {
        val nr = naviRoutes() ?: return
        nr.current { g, _ ->
            a.runOnUiThread {
                val demo = liveDemo ?: return@runOnUiThread
                val texts = g?.steps?.map { it.description }?.filter { it != "경유" } ?: return@runOnUiThread
                val took = demo.guidedIndex(texts)
                android.util.Log.i("SteerScore", "route switch check: wanted $index, navigation follows $took")
                if (took != index && demo.turnTexts.containsKey(index)) {
                    // One more try through another spot of that route
                    val next = demo.waypointFor(index, attempt + 1)
                    if (attempt == 0 && next != null && nr.routeVia(next, label)) {
                        overlay.postDelayed({ checkSwitched(index, label, attempt + 1) }, 16_000)
                        status.text = "내비가 '$label' 경로로 정확히 바뀌지 않아 다른 지점으로 다시 맞춰요"
                        return@runOnUiThread
                    }
                    nr.dropWaypoint()
                    nr.openRouteChoice()
                    guidedIndex = took
                    status.text = "내비가 '$label' 경로로 정확히 바뀌지 않아 경유지를 지웠어요. 내비 경로 화면에서 '$label'을(를) 눌러 주세요"
                }
            }
        }
    }

    /** The driver confirmed: switch the navigation (live) or mark the choice (demo). */
    private fun confirm(r: RouteProfile) {
        val label = RouteAdvisor.optionLabel(r.options)
        pending = null
        picked = r
        status.text = if (liveRoutes != null && liveAltMap != null && !liveCompared) {
            // The navigation's alternatives have no RouteOption: send it through a spot only that route passes, then check
            // its turn list; if it took another way, drop the waypoint and let the driver pick in the navigation instead
            val nr = naviRoutes()
            val index = liveRoutes?.indexOf(r) ?: -1
            val wp = if (index > 0) liveDemo?.waypointFor(index) else null
            when {
                nr == null -> "내비에 경로를 요청하지 못했어요"
                index == 0 -> {
                    nr.dropWaypoint()
                    guidedIndex = 0
                    "내비가 '$label' 경로로 다시 안내해요"
                }
                wp != null && nr.routeVia(wp, label) -> {
                    nr.chosenAlt = index
                    guidedIndex = index
                    overlay.postDelayed({ checkSwitched(index, label, 0) }, 16_000)
                    "내비가 '$label' 경로로 바꿔 안내해요. 내비 화면의 카운트다운이 끝나면 시작해요"
                }
                nr.openRouteChoice() -> "내비 경로 화면에서 '$label'을(를) 누르세요. 누르지 않으면 몇 초 뒤 추천 경로로 안내해요"
                else -> "내비에 경로를 요청하지 못했어요"
            }
        } else if (liveRoutes != null) {
            val ok = naviRoutes()?.select(r.options.first()) == true
            if (ok) overlay.postDelayed({ a.startActivity(android.content.Intent(a, a.javaClass).addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }, 1500)
            if (ok) "내비 경로를 '$label'(으)로 바꿨어요" else "내비 경로 옵션을 바꾸지 못했어요"
        } else {
            "'$label' 선택 · 데모 구간이라 표시만 해요"
        }
        renderRoutes()
        renderConfirm()
    }

    private fun countsText(r: RouteProfile): String {
        val order = listOf(
            Situation.IC_ENTRY to "IC 진입", Situation.MERGE to "합류", Situation.IC_EXIT to "IC 진출", Situation.FORK to "분기",
            Situation.INTERSECTION to "교차로 회전", Situation.ROUNDABOUT to "회전교차로", Situation.TOLL to "톨게이트",
        )
        return order.mapNotNull { (s, l) -> r.counts[s]?.takeIf { it > 0 }?.let { "$l $it" } }.joinToString(" · ")
            .ifEmpty { "진출입·회전 없음" }
    }

    private fun row(selected: Boolean, onClick: () -> Unit) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16, 10, 16, 10)
        background = GradientDrawable().apply {
            cornerRadius = 12f
            setColor(a.getColor(if (selected) R.color.panel_line else R.color.panel))
            if (selected) setStroke(3, a.getColor(R.color.accent))
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun text(s: String, size: Float, bold: Boolean = false, muted: Boolean = false) = TextView(a).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextColor(a.getColor(if (muted) R.color.text_muted else R.color.text))
    }

    private companion object {
        val NUMBERS = listOf("①", "②", "③", "④", "⑤")
    }

    private fun scoreColor(score: Int) = when {
        score >= 90 -> R.color.good
        score >= 75 -> R.color.warn
        else -> R.color.bad
    }
}
