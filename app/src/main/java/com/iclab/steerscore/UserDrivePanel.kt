package com.iclab.steerscore

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.iclab.steerscore.core.Cause
import com.iclab.steerscore.core.DrivingEvent
import com.iclab.steerscore.core.DrivingSample
import com.iclab.steerscore.core.GeoPoint
import com.iclab.steerscore.core.RouteMapData
import com.iclab.steerscore.core.RouteMaps
import com.iclab.steerscore.core.TripAnalyzer
import com.iclab.steerscore.ui.EventText
import com.iclab.steerscore.ui.LiveMapView
import com.iclab.steerscore.ui.SeriesView
import java.util.Locale

/**
 * The driver's screen (시연 모드 off), in place of the dashboard row: one score and the latest deductions on the left,
 * the map following the car's position in the middle (the road driven so far, numbered deductions), and speed and
 * steering as two separate graphs on the right. While parked it keeps the last drive (or shows where the car is).
 */
class UserDrivePanel(private val a: Activity, private val dashboard: View) {

    private val density = a.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val map = LiveMapView(a).apply { spanM = 1800.0 }
    private val speed = SeriesView(a).apply { yMax = 60f; unit = "km/h" }
    private val steer = SeriesView(a).apply { yMax = 60f; symmetric = true; upLabel = "왼쪽"; downLabel = "오른쪽" }
    private val score = TextView(a).apply { textSize = 96f; setTextColor(a.getColor(R.color.accent)); setTypeface(typeface, Typeface.BOLD) }
    private val caption = text(21f, muted = true)
    private val stats = text(21f, muted = true)
    private val speedTitle = title("속도")
    private val steerTitle = title("조향 (핸들 각도)")
    private val feed = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val root: LinearLayout

    private var startMs = 0L
    private var lastDrawMs = -1_000_000L
    private val track = mutableListOf<GeoPoint>()
    private val speedPts = mutableListOf<SeriesView.Point>()
    private val steerPts = mutableListOf<SeriesView.Point>()
    private val speedDots = mutableListOf<SeriesView.Dot>()
    private val steerDots = mutableListOf<SeriesView.Dot>()
    private val marks = mutableListOf<LiveMapView.Mark>()
    private val recent = ArrayDeque<String>()
    private var roadsCentre: GeoPoint? = null

    var active = false
        private set

    init {
        root = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            // Left: the score and what just happened
            addView(panel().apply {
                addView(title("SteerScore"))
                addView(score)
                addView(caption)
                addView(stats)
                addView(title("방금 감점").apply { setPadding(0, dp(24f), 0, 0) })
                addView(feed)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.8f).apply { marginEnd = dp(20f) })
            // Middle: where the car is
            addView(panel().apply {
                addView(title("내 위치 · 지나온 길"))
                addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12f) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.5f).apply { marginEnd = dp(20f) })
            // Right: speed and steering, apart
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(panel().apply {
                    addView(speedTitle)
                    addView(speed, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8f) })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(panel().apply {
                    addView(steerTitle)
                    addView(steer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8f) })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(20f) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f))
        }
        val parent = dashboard.parent as ViewGroup
        parent.addView(root, parent.indexOfChild(dashboard) + 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        idleText()
        renderFeed()
    }

    /** Shown instead of the dashboard (the driver's app), or hidden (시연 모드). */
    fun show(on: Boolean) {
        root.visibility = if (on) View.VISIBLE else View.GONE
    }

    fun start() {
        active = true
        startMs = -1L
        lastDrawMs = -1_000_000L
        track.clear(); speedPts.clear(); steerPts.clear(); speedDots.clear(); steerDots.clear(); marks.clear(); recent.clear()
        speed.windowMs = 120_000L
        steer.windowMs = 120_000L
        score.text = "100"
        caption.text = "주행 중"
        stats.text = ""
        speedTitle.text = "속도"
        steerTitle.text = "조향 (핸들 각도)"
        map.estimated = false
        renderFeed()
    }

    /** The drive ended: keep it on screen as the last drive, whole. */
    fun stop(steerScore: Int?, baseline: Int?) {
        if (!active) return
        active = false
        caption.text = if (baseline != null) "지난 주행 · 기존 방식(속도만) ${baseline}점" else "지난 주행"
        steerScore?.let { score.text = it.toString() }
        speed.windowMs = null
        steer.windowMs = null
        speed.update(speedPts.toList(), speedDots.toList())
        steer.update(steerPts.toList(), steerDots.toList())
        map.update(track.toList(), marks.toList())
    }

    /** Parked with no drive yet: the car's position from the navigation, if it gave one. */
    fun idle(here: GeoPoint?) {
        if (active || track.isNotEmpty() || here == null) return
        refreshRoads(here)
        map.update(listOf(here), emptyList())
    }

    fun onSample(an: TripAnalyzer, s: DrivingSample, deductions: List<DrivingEvent>, ruleId: String) {
        if (!active) return
        if (startMs < 0) startMs = s.timeMs
        for (e in deductions) {
            val cause = Cause.of(e)
            val steering = cause?.steering ?: e.isSteering
            val color = a.getColor(if (steering) R.color.bad else R.color.warn)
            val at = an.track.lastOrNull { it.timeMs <= e.timeMs }?.point ?: an.track.lastOrNull()?.point
            if (at != null) marks += LiveMapView.Mark(at, marks.size + 1, steering)
            (if (steering) steerDots else speedDots) += SeriesView.Dot(e.timeMs, color)
            recent.addFirst("${EventText.clock(e.timeMs - startMs)}  ${cause?.label ?: EventText.title(e)}")
            while (recent.size > 4) recent.removeLast()
            renderFeed()
        }
        if (s.timeMs - lastDrawMs < 250) return
        lastDrawMs = s.timeMs
        speedPts += SeriesView.Point(s.timeMs, s.speedKmh, s.speedLimitKmh)
        steerPts += SeriesView.Point(s.timeMs, s.steeringDeg)
        an.track.lastOrNull()?.point?.let { p ->
            if (track.lastOrNull()?.let { it.distanceM(p) > 3 } != false) track += p
            refreshRoads(p)
        }
        // On the emulator (and in a GPS gap) the position is dead-reckoned: say so instead of drawing it over the roads
        map.estimated = !an.onFixNow
        score.text = an.steerScore(ruleId).toString()
        stats.text = "%.0f km/h · %.1f km · %s".format(Locale.US, s.speedKmh, an.distanceKm, EventText.clock(s.timeMs - startMs))
        speedTitle.text = "속도  %.0f km/h".format(Locale.US, s.speedKmh) + (s.speedLimitKmh?.let { " · 제한 %.0f".format(Locale.US, it) } ?: "")
        steerTitle.text = "조향 (핸들 각도)  %+.0f°".format(Locale.US, s.steeringDeg)
        speed.update(speedPts.toList(), speedDots.toList())
        steer.update(steerPts.toList(), steerDots.toList())
        map.update(track.toList(), marks.toList())
    }

    /** The bundled road map around the car, reloaded once it has moved a kilometre. */
    private fun refreshRoads(p: GeoPoint) {
        val c = roadsCentre
        if (c != null && c.distanceM(p) < 1_000) return
        roadsCentre = p
        val d = 0.012
        val roads: RouteMapData? = RouteMaps.roadsAround(listOf(GeoPoint(p.lat - d, p.lon - d), GeoPoint(p.lat + d, p.lon + d)))
        map.start(emptyList(), roads)
    }

    private fun idleText() {
        score.text = "–"
        caption.text = "주행을 시작하면 점수가 매겨져요"
    }

    private fun renderFeed() {
        feed.removeAllViews()
        if (recent.isEmpty()) feed.addView(text(22f, muted = true).apply { text = "감점이 없어요"; setPadding(0, dp(10f), 0, 0) })
        recent.forEachIndexed { i, line ->
            feed.addView(text(if (i == 0) 25f else 21f, muted = i > 0).apply {
                text = line
                if (i == 0) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(10f), 0, 0)
            })
        }
    }

    private fun panel() = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.panel_bg)
        setPadding(dp(24f), dp(20f), dp(24f), dp(20f))
    }

    private fun title(s: String) = TextView(a).apply {
        text = s; textSize = 23f; setTextColor(a.getColor(R.color.text_muted)); setTypeface(typeface, Typeface.BOLD)
    }

    private fun text(size: Float, muted: Boolean = false) = TextView(a).apply {
        textSize = size; setTextColor(a.getColor(if (muted) R.color.text_muted else R.color.text))
    }
}
