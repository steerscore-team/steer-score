package com.iclab.steerscore

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.iclab.steerscore.core.Cause
import com.iclab.steerscore.core.DrivingEvent
import com.iclab.steerscore.core.DrivingSample
import com.iclab.steerscore.core.GeoPoint
import com.iclab.steerscore.core.TripAnalyzer
import com.iclab.steerscore.ui.EventText
import com.iclab.steerscore.ui.LiveMapView
import com.iclab.steerscore.ui.LiveScoreView
import java.util.Locale

/**
 * 시연 모드 screen during a drive on real roads (the simulated live drive, or replaying ⑦): the car moving on the map,
 * both scores over time, and the latest deductions. It takes the place of the dashboard row above the bottom bar, so
 * 주행 종료 stays where it is. The driver's app never shows it: while driving, the driver looks at the navigation.
 */
class LiveDrivePanel(private val a: Activity, private val dashboard: View) {

    private val density = a.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val map = LiveMapView(a)
    private val graph = LiveScoreView(a)
    private val steerValue = big(a.getColor(R.color.accent))
    private val baseValue = big(a.getColor(R.color.text_muted))
    private val stats = text(18f, muted = true)
    private val feed = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val root: LinearLayout

    private var startMs = 0L
    private var lastDrawMs = -1_000_000L
    private val points = mutableListOf<LiveScoreView.Point>()
    private val dots = mutableListOf<LiveScoreView.Dot>()
    private val marks = mutableListOf<LiveMapView.Mark>()
    private val recent = ArrayDeque<String>()

    var active = false
        private set

    init {
        root = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(panel().apply {
                addView(title("주행 지도 · 감점이 생긴 곳"))
                addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12f) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.55f).apply { marginEnd = dp(20f) })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(panel().apply {
                    addView(LinearLayout(a).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(scoreBox("SteerScore (속도+조향)", steerValue), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(scoreBox("기존 방식 (속도)", baseValue), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    })
                    addView(stats)
                    addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190f)).apply { topMargin = dp(12f) })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(panel().apply {
                    addView(title("방금 감점"))
                    addView(feed)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(20f) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        // Same place and size as the dashboard row
        val parent = dashboard.parent as ViewGroup
        parent.addView(root, parent.indexOfChild(dashboard) + 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** A new drive on real roads: [route] is the whole path when known ahead (a recorded drive), [totalMs] its length. */
    fun start(route: List<GeoPoint>, totalMs: Long?, startMs: Long) {
        active = true
        this.startMs = startMs
        lastDrawMs = -1_000_000L
        points.clear(); dots.clear(); marks.clear(); recent.clear()
        graph.totalMs = totalMs
        map.start(route, com.iclab.steerscore.core.RouteMaps.roadsAround(route))
        steerValue.text = "100"
        baseValue.text = "100"
        stats.text = ""
        renderFeed()
        dashboard.visibility = View.GONE
        root.visibility = View.VISIBLE
    }

    fun stop() {
        if (!active) return
        active = false
        root.visibility = View.GONE
        dashboard.visibility = View.VISIBLE
    }

    /** Each sample: new deductions go on the map, the graph and the list; the screen redraws about 4 times a second. */
    fun onSample(an: TripAnalyzer, s: DrivingSample, deductions: List<DrivingEvent>, ruleId: String) {
        if (!active) return
        for (e in deductions) {
            val cause = Cause.of(e)
            val steering = cause?.steering ?: e.isSteering
            val at = an.track.lastOrNull { it.timeMs <= e.timeMs }?.point ?: an.track.lastOrNull()?.point
            if (at != null) marks += LiveMapView.Mark(at, marks.size + 1, steering)
            dots += LiveScoreView.Dot(e.timeMs, steering)
            recent.addFirst("${marks.size}.  ${EventText.clock(e.timeMs - startMs)}  ${cause?.label ?: EventText.title(e)}  −%.1f".format(Locale.US, e.penalty))
            while (recent.size > 5) recent.removeLast()
            renderFeed()
        }
        if (s.timeMs - lastDrawMs < 250) return
        lastDrawMs = s.timeMs
        val steer = an.steerScore(ruleId)
        val base = an.baselineScore(ruleId)
        if (points.isEmpty() || s.timeMs - points.last().timeMs >= 1000) points += LiveScoreView.Point(s.timeMs, base, steer)
        steerValue.text = steer.toString()
        baseValue.text = base.toString()
        stats.text = "%.0f km/h · 핸들 %+.0f° · %.1f km · %s".format(Locale.US, s.speedKmh, s.steeringDeg, an.distanceKm, EventText.clock(s.timeMs - startMs))
        graph.update(points.toList(), dots.toList())
        map.update(an.track.map { it.point }, marks.toList())
    }

    private fun renderFeed() {
        feed.removeAllViews()
        if (recent.isEmpty()) feed.addView(text(21f, muted = true).apply { text = "아직 감점이 없어요"; setPadding(0, dp(10f), 0, 0) })
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
        text = s; textSize = 22f; setTextColor(a.getColor(R.color.text_muted)); setTypeface(typeface, Typeface.BOLD)
    }

    private fun text(size: Float, muted: Boolean = false) = TextView(a).apply {
        textSize = size; setTextColor(a.getColor(if (muted) R.color.text_muted else R.color.text))
    }

    private fun big(color: Int) = TextView(a).apply { textSize = 64f; setTextColor(color); setTypeface(typeface, Typeface.BOLD) }

    private fun scoreBox(label: String, value: TextView) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        addView(text(17f, muted = true).apply { text = label })
        addView(value)
    }
}
