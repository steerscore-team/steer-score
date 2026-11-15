package com.iclab.steerscore.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.iclab.steerscore.R
import com.iclab.steerscore.core.GeoPoint
import com.iclab.steerscore.core.RoadClass
import com.iclab.steerscore.core.ReportItem
import com.iclab.steerscore.core.Situation
import com.iclab.steerscore.core.TrackPoint
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The trip report map. Overview: the whole trip, coloured by road class when the navigation knew it, with every
 * deduction numbered. Selecting an event zooms to it and draws the road as the data describes it, since there is
 * no base map: a road band with lanes (width from the road class), the navigation's situation sections (merge,
 * IC exit, ...), speed limits, direction arrows, the car's own path (a lane change shows as an S) and the event's
 * details.
 */
class TripMapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Shows the event of [items] at this index, or the whole trip when null. */
    var onSelect: ((Int?) -> Unit)? = null

    private var track: List<TrackPoint> = emptyList()
    private var items: List<ReportItem> = emptyList()
    private var credit: String? = null
    private var selected: Int? = null

    // Local metres around the trip, x east / y north
    private var lat0 = 0.0
    private var lon0 = 0.0
    private var kx = 1.0
    private val ky = GeoPoint.METERS_PER_DEG_LAT
    private var xs = DoubleArray(0)
    private var ys = DoubleArray(0)
    private var cxs = DoubleArray(0)   // road band centre: the recorded path (no road geometry is available)
    private var cys = DoubleArray(0)

    // View: centre (m) and scale (px per m), animated between overview and zoom
    private var viewX = 0.0
    private var viewY = 0.0
    private var scale = 1.0
    private var animator: ValueAnimator? = null
    /** While the view moves only the light route is drawn; the detailed road is drawn once it settles. */
    private var moving = false

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun color(id: Int) = context.getColor(id)

    /** Real roads under the path (only for a path recorded from real location fixes). */
    private var roads: com.iclab.steerscore.core.RouteMapData? = null

    /** Each deduction's points in the callout (시연 모드); the driver sees only that it was a deduction. */
    var showPoints = true

    fun show(track: List<TrackPoint>, items: List<ReportItem>, credit: String?, roads: com.iclab.steerscore.core.RouteMapData? = null) {
        this.roads = roads
        this.track = track
        this.items = items
        this.credit = credit
        selected = null
        prepare()
        post { fitAll(animate = false) }
    }

    fun select(index: Int?) {
        selected = index
        if (index == null) fitAll(animate = true) else zoomTo(index)
    }

    private fun prepare() {
        if (track.isEmpty()) return
        lat0 = track.map { it.point.lat }.average()
        lon0 = track.map { it.point.lon }.average()
        kx = GeoPoint.METERS_PER_DEG_LAT * cos(lat0 * PI / 180)
        xs = DoubleArray(track.size) { (track[it].point.lon - lon0) * kx }
        ys = DoubleArray(track.size) { (track[it].point.lat - lat0) * ky }
        // The road band follows the recorded path itself. The navigation gives no road geometry or lane count, so
        // nothing is drawn that would claim where the lanes are; a band around a smoothed line drifted off the path
        // on ramps and made the car look as if it left the road.
        cxs = xs.copyOf()
        cys = ys.copyOf()
    }

    private fun fitAll(animate: Boolean) {
        if (xs.isEmpty() || width == 0) return
        val pad = dp(48f)
        val spanX = max(xs.max() - xs.min(), 200.0)
        val spanY = max(ys.max() - ys.min(), 200.0)
        val s = min((width - 2 * pad) / spanX, (height - 2 * pad) / spanY)
        moveTo((xs.max() + xs.min()) / 2, (ys.max() + ys.min()) / 2, s, animate)
    }

    private fun zoomTo(index: Int) {
        val item = items.getOrNull(index) ?: return
        val i = indexAt(item.offsetMs + startMs())
        // About 180 m across: lanes are clearly visible, a lane change's 3.5 m shift shows as an S
        moveTo(xs[i], ys[i], width / 180.0, animate = true)
    }

    private fun moveTo(x: Double, y: Double, s: Double, animate: Boolean) {
        animator?.cancel()
        if (!animate) {
            viewX = x; viewY = y; scale = s
            invalidate()
            return
        }
        val (x0, y0, s0) = Triple(viewX, viewY, scale)
        moving = true
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                viewX = x0 + (x - x0) * f
                viewY = y0 + (y - y0) * f
                // Zoom geometrically so the motion feels even
                scale = s0 * Math.pow(s / s0, f.toDouble())
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    moving = false
                    invalidate()
                }
            })
            start()
        }
    }

    private fun startMs() = track.firstOrNull()?.timeMs ?: 0L

    private fun indexAt(timeMs: Long): Int {
        val i = track.binarySearchBy(timeMs) { it.timeMs }
        return (if (i >= 0) i else -i - 1).coerceIn(0, track.size - 1)
    }

    private fun px(x: Double) = (width / 2 + (x - viewX) * scale).toFloat()
    private fun py(y: Double) = (height / 2 - (y - viewY) * scale).toFloat()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (selected == null) fitAll(animate = false) else zoomTo(selected!!)
    }

    // ---- Paints ----
    private val bg = Paint().apply { color = 0xFF0F141A.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.text); textSize = dp(18f) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.text_muted); textSize = dp(15f) }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(15f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()

    private fun classColor(c: RoadClass?) = when (c) {
        RoadClass.EXPRESSWAY -> 0xFF3DA9FC.toInt()
        RoadClass.RAMP -> 0xFFFFB020.toInt()
        RoadClass.GENERAL -> 0xFF9AA5B1.toInt()
        null -> 0xFF5B6675.toInt()
    }

    private fun situationColor(s: Situation) = when (s) {
        Situation.MERGE -> 0xFFB388FF.toInt()
        Situation.IC_ENTRY -> 0xFF26C6DA.toInt()
        Situation.IC_EXIT -> 0xFFFFB020.toInt()
        Situation.FORK -> 0xFF66BB6A.toInt()
        Situation.ROUNDABOUT -> 0xFFEC407A.toInt()
        Situation.TOLL -> 0xFFFFEE58.toInt()
        Situation.INTERSECTION -> 0xFF8BC34A.toInt()
        else -> 0
    }

    /** Fixed on-screen road width: lane counts are unknown, so the width must not suggest any. */
    private fun roadPx() = dp(26f)

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        if (track.size < 2) {
            canvas.drawText("주행 경로가 기록되지 않았어요", dp(20f), height / 2f, small)
            return
        }
        val zoomed = scale > 0.6 && !moving   // settled above 0.6 px per metre: draw the road as a road
        drawBackgroundRoads(canvas)
        if (zoomed) drawRoad(canvas) else drawOverview(canvas)
        drawEvents(canvas, zoomed)
        drawFurniture(canvas)
    }

    /** OpenStreetMap roads and rivers under the recorded path, dim so the path stays on top. */
    private fun drawBackgroundRoads(canvas: Canvas) {
        val r = roads ?: return
        stroke.pathEffect = null
        fun line(pts: List<GeoPoint>) {
            path.reset()
            pts.forEachIndexed { i, p ->
                val x = px((p.lon - lon0) * kx)
                val y = py((p.lat - lat0) * ky)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, stroke)
        }
        stroke.color = 0xFF1C3550.toInt()
        stroke.strokeWidth = dp(4f)
        r.rivers.forEach(::line)
        for ((cls, color, width) in listOf(Triple("primary", 0xFF2A333E.toInt(), 2.5f), Triple("trunk", 0xFF36404D.toInt(), 3.5f),
            Triple("motorway", 0xFF4A4436.toInt(), 4.5f))) {
            stroke.color = color
            stroke.strokeWidth = dp(width)
            r.roads.filter { it.first == cls }.forEach { line(it.second) }
        }
    }

    /** Whole trip: the route coloured by road class, thick enough to read. */
    private fun drawOverview(canvas: Canvas) {
        stroke.pathEffect = null
        stroke.strokeWidth = dp(6f)
        var i = 0
        while (i < track.size - 1) {
            val c = track[i].road?.roadClass
            var j = i
            path.reset()
            path.moveTo(px(xs[i]), py(ys[i]))
            while (j < track.size - 1 && track[j].road?.roadClass == c) {
                j++
                path.lineTo(px(xs[j]), py(ys[j]))
            }
            stroke.color = classColor(c)
            canvas.drawPath(path, stroke)
            i = j
        }
        fill.color = 0xFF8A96A3.toInt()
        canvas.drawCircle(px(xs[0]), py(ys[0]), dp(6f), fill)
        canvas.drawText("출발", px(xs[0]) + dp(10f), py(ys[0]) - dp(8f), small)
        val n = track.size - 1
        canvas.drawCircle(px(xs[n]), py(ys[n]), dp(6f), fill)
        canvas.drawText("도착", px(xs[n]) + dp(10f), py(ys[n]) - dp(8f), small)
    }

    /**
     * Zoomed in: what the navigation says about the road, along the recorded path. Road class (band edge colour),
     * the navigation's situation sections, speed limits and direction. No lanes: the navigation does not give them.
     */
    private fun drawRoad(canvas: Canvas) {
        val visible = visibleRange()
        val w = roadPx()
        // Road band, edged in the road class colour
        forRuns(visible, { track[it].road?.roadClass }) { a, b, c ->
            centrePath(a, b)
            stroke.pathEffect = null
            stroke.color = classColor(c); stroke.strokeWidth = w + dp(4f); canvas.drawPath(path, stroke)
            stroke.color = 0xFF2A313A.toInt(); stroke.strokeWidth = w; canvas.drawPath(path, stroke)
            // Road class tag where the run is closest to the view centre
            labelAt(canvas, nearestToCentre(a, b), c?.label ?: "도로 정보 없음", classColor(c), extra = 0f)
        }
        // Navigation situation sections as tinted overlays with their name
        forRuns(visible, { track[it].road?.situation?.takeIf { s -> situationColor(s) != 0 } }) { a, b, s ->
            if (s == null) return@forRuns
            centrePath(a, b)
            stroke.color = situationColor(s); stroke.alpha = 70; stroke.strokeWidth = w
            canvas.drawPath(path, stroke)
            stroke.alpha = 255
            labelAt(canvas, nearestToCentre(a, b), s.label, situationColor(s), extra = dp(34f))
        }
        // Speed limit signs where the limit changes, and every ~300 m otherwise
        var lastLimit: Float? = null
        var lastSignM = -1e9
        var dist = 0.0
        for (i in visible) {
            if (i > visible.first) dist += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            val lim = track[i].speedLimitKmh ?: continue
            if (lim != lastLimit || dist - lastSignM > 300) {
                limitSign(canvas, i, lim)
                lastLimit = lim
                lastSignM = dist
            }
        }
        // Direction arrows along the centre line every ~80 m
        dist = 0.0
        var nextArrow = 40.0
        for (i in visible) {
            if (i > visible.first) dist += hypot(cxs[i] - cxs[i - 1], cys[i] - cys[i - 1])
            if (dist >= nextArrow && i + 1 < track.size) {
                arrow(canvas, i)
                nextArrow += 80.0
            }
        }
        // The car's recorded path (a lane change shows as an S; how far it moved sideways is in the event box)
        stroke.color = 0xFF4DD0E1.toInt()
        stroke.strokeWidth = dp(2.5f)
        path.reset()
        path.moveTo(px(xs[visible.first]), py(ys[visible.first]))
        for (i in visible.drop(1)) path.lineTo(px(xs[i]), py(ys[i]))
        canvas.drawPath(path, stroke)
    }

    private fun drawEvents(canvas: Canvas, zoomed: Boolean) {
        val start = startMs()
        // Highlight the selected event's own stretch of path
        selected?.let { sel ->
            val it = items.getOrNull(sel) ?: return@let
            val a = indexAt(it.offsetMs + start)
            val b = indexAt(it.offsetMs + start + durationMs(it))
            stroke.color = eventColor(it)
            stroke.strokeWidth = if (zoomed) dp(5f) else dp(9f)
            path.reset()
            path.moveTo(px(xs[a]), py(ys[a]))
            for (i in a + 1..max(a + 1, b).coerceAtMost(track.size - 1)) path.lineTo(px(xs[i]), py(ys[i]))
            canvas.drawPath(path, stroke)
        }
        items.forEachIndexed { n, it ->
            val i = indexAt(it.offsetMs + start)
            val x = px(xs[i])
            val y = py(ys[i])
            if (x < -dp(40f) || x > width + dp(40f) || y < -dp(40f) || y > height + dp(40f)) return@forEachIndexed
            if (it.event.penalty == 0f) {
                fill.color = color(R.color.good)
                canvas.drawCircle(x, y, dp(if (n == selected) 9f else 5f), fill)
                return@forEachIndexed
            }
            val r = dp(if (n == selected) 17f else 13f)
            fill.color = eventColor(it)
            fill.alpha = 90
            canvas.drawCircle(x, y, r * 1.6f, fill)
            fill.alpha = 255
            canvas.drawCircle(x, y, r, fill)
            badge.color = color(R.color.bg)
            canvas.drawText("${number(n)}", x, y + dp(5.5f), badge)
        }
        if (zoomed) selected?.let { callout(canvas, it) }
    }

    /** Green for a normal (0-point) event, red for a steering deduction, orange for a speed deduction. */
    /** By the size of the deduction (red large, orange small, green none), as in the report's list. */
    private fun eventColor(it: ReportItem) = color(EventText.colorRes(it.event))

    /** Deductions are numbered in time order; normal (0-point) events are dots without numbers. */
    private fun number(index: Int) = items.take(index + 1).count { it.event.penalty > 0f }

    private fun durationMs(it: ReportItem): Long = when (val e = it.event) {
        is com.iclab.steerscore.core.LaneChange -> e.durationMs
        is com.iclab.steerscore.core.LaneDeparture -> e.durationMs
        is com.iclab.steerscore.core.IntersectionTurn -> e.durationMs
        is com.iclab.steerscore.core.Cornering -> e.durationMs
        else -> 1500L
    }

    /** Box next to the selected event: what, where, speed, signal, sideways force. */
    private fun callout(canvas: Canvas, sel: Int) {
        val it = items.getOrNull(sel) ?: return
        val i = indexAt(it.offsetMs + startMs())
        val tp = track[i]
        val lines = listOf(
            "${EventText.clock(it.offsetMs)}  ${EventText.title(it.event)}" +
                when {
                    it.event.penalty > 0f -> if (showPoints) "  -%.1f".format(Locale.US, it.event.penalty) else "  (감점)"
                    "정상" in EventText.title(it.event) -> ""
                    else -> "  (정상)"
                },
            "${it.place}${tp.road?.roadClass?.let { c -> if (c.label != it.place) " · ${c.label}" else "" } ?: ""}" +
                " · %.0f km/h".format(Locale.US, tp.speedKmh) +
                (tp.speedLimitKmh?.let { l -> " (제한 %.0f)".format(Locale.US, l) } ?: ""),
            EventText.detail(it.event),
        )
        val w = lines.maxOf { l -> text.measureText(l) } + dp(28f)
        val h = dp(30f) * lines.size + dp(16f)
        val x = (px(xs[i]) + dp(34f)).coerceAtMost(width - w - dp(12f))
        val y = (py(ys[i]) - h - dp(24f)).coerceAtLeast(dp(12f))
        fill.color = 0xEE0B0F14.toInt()
        canvas.drawRoundRect(RectF(x, y, x + w, y + h), dp(10f), dp(10f), fill)
        stroke.color = eventColor(it)
        stroke.strokeWidth = dp(2f)
        canvas.drawRoundRect(RectF(x, y, x + w, y + h), dp(10f), dp(10f), stroke)
        lines.forEachIndexed { k, l -> canvas.drawText(l, x + dp(14f), y + dp(32f) + k * dp(30f), if (k == 0) text else small) }
    }

    private fun drawFurniture(canvas: Canvas) {
        // Scale bar about a fifth of the width
        val target = width / 5f / scale
        val meters = listOf(10, 20, 50, 100, 200, 500, 1000, 2000, 5000).lastOrNull { it <= target } ?: 10
        val len = (meters * scale).toFloat()
        val by = height - dp(22f)
        stroke.color = color(R.color.text_muted); stroke.strokeWidth = dp(2f); stroke.pathEffect = null
        canvas.drawLine(dp(20f), by, dp(20f) + len, by, stroke)
        canvas.drawText(if (meters >= 1000) "${meters / 1000} km" else "$meters m", dp(26f) + len, by + dp(5f), small)
        canvas.drawText("N ↑", width - dp(52f), dp(28f), small)
        val note = listOfNotNull(
            when {
                scale > 0.6 -> "도로 띠 = 실제 주행 경로 · 차선 정보 없음(내비 미제공)"
                track.any { it.road?.roadClass != null } -> "색: 고속도로 파랑 · 진출입로 주황 · 일반도로 회색"
                else -> "도로 유형 정보 없음 (내비 길안내 없이 주행)"
            },
            credit,
        ).joinToString("  ·  ")
        canvas.drawText(note, width - small.measureText(note) - dp(16f), height - dp(16f), small)
    }

    // ---- Geometry helpers ----

    private fun visibleRange(): IntRange {
        // The detailed road only near the view centre: enough for the screen, cheap to draw
        val margin = min(max(width, height) / scale, 400.0)
        var a = 0
        while (a < track.size - 1 && abs(xs[a] - viewX) > margin || a < track.size - 1 && abs(ys[a] - viewY) > margin) a++
        var b = track.size - 1
        while (b > a && (abs(xs[b] - viewX) > margin || abs(ys[b] - viewY) > margin)) b--
        return a..max(a + 1, b).coerceAtMost(track.size - 1)
    }

    private fun nearestToCentre(a: Int, b: Int): Int =
        (a..b).minByOrNull { hypot(cxs[it] - viewX, cys[it] - viewY) } ?: a

    private fun <T> forRuns(range: IntRange, key: (Int) -> T, block: (Int, Int, T) -> Unit) {
        var i = range.first
        while (i < range.last) {
            val k = key(i)
            var j = i
            while (j < range.last && key(j + 1) == k) j++
            block(i, max(j, i + 1).coerceAtMost(range.last), k)
            i = j + 1
        }
    }

    private fun centrePath(a: Int, b: Int) {
        path.reset()
        path.moveTo(px(cxs[a]), py(cys[a]))
        for (i in a + 1..b) path.lineTo(px(cxs[i]), py(cys[i]))
    }

    /** Tag on the left of the road (signs stand on the right); [extra] stacks tags outward. */
    private fun labelAt(canvas: Canvas, i: Int, label: String, c: Int, extra: Float) {
        val x = px(cxs[i])
        val y = py(cys[i])
        if (x < 0 || x > width || y < 0 || y > height) return
        val j = min(track.size - 1, i + 3)
        val h = atan2(cys[j] - cys[i], cxs[j] - cxs[i])
        val off = roadPx() / 2 + dp(22f) + extra
        val lx = (x - sin(h) * off).toFloat()
        val ly = (y - cos(h) * off).toFloat()
        val w = text.measureText(label) + dp(16f)
        fill.color = 0xDD0B0F14.toInt()
        canvas.drawRoundRect(RectF(lx - w / 2, ly - dp(16f), lx + w / 2, ly + dp(10f)), dp(6f), dp(6f), fill)
        fill.color = c
        canvas.drawRect(lx - w / 2, ly + dp(7f), lx + w / 2, ly + dp(10f), fill)
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(label, lx, ly + dp(3f), text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun limitSign(canvas: Canvas, i: Int, limit: Float) {
        val j = min(track.size - 1, i + 3)
        val h = atan2(cys[j] - cys[i], cxs[j] - cxs[i])
        val off = roadPx() / 2 + dp(26f)
        val x = (px(cxs[i]) + sin(h) * off).toFloat()   // right side of the road, like a real sign
        val y = (py(cys[i]) + cos(h) * off).toFloat()
        fill.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(x, y, dp(17f), fill)
        stroke.color = 0xFFE53935.toInt(); stroke.strokeWidth = dp(4f); canvas.drawCircle(x, y, dp(15f), stroke)
        badge.color = 0xFF111111.toInt()
        canvas.drawText("%.0f".format(Locale.US, limit), x, y + dp(5.5f), badge)
    }

    private fun arrow(canvas: Canvas, i: Int) {
        val j = min(track.size - 1, i + 3)
        val h = atan2(-(cys[j] - cys[i]), cxs[j] - cxs[i])   // screen angle (y down)
        val x = px(cxs[i])
        val y = py(cys[i])
        val l = dp(9f)
        path.reset()
        path.moveTo(x + (cos(h) * l).toFloat(), y + (sin(h) * l).toFloat())
        path.lineTo(x + (cos(h + 2.5) * l).toFloat(), y + (sin(h + 2.5) * l).toFloat())
        path.lineTo(x + (cos(h - 2.5) * l).toFloat(), y + (sin(h - 2.5) * l).toFloat())
        path.close()
        fill.color = 0x99E8ECF0.toInt()
        canvas.drawPath(path, fill)
    }
}
