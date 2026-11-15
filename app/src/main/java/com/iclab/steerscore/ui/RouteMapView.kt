package com.iclab.steerscore.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.iclab.steerscore.core.GeoPoint
import com.iclab.steerscore.core.RouteMapData
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A navigation-style map of a demo pair's candidate routes, drawn by the app from bundled OpenStreetMap data (no tiles,
 * no network): light background, roads by class, rivers, every candidate route with a white casing (the highlighted one
 * blue and on top, the others grey), start and destination pins and a "① 97점 · 22분" tag on each route. Tapping a
 * route reports it through [onRouteTap].
 */
class RouteMapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** One candidate on the map: its index in the map data, the tag text, and whether it is highlighted. */
    data class Candidate(val index: Int, val tag: String, val highlighted: Boolean)

    private var data: RouteMapData? = null
    private var candidates: List<Candidate> = emptyList()
    /** A line of text in the top-left corner of the map. */
    private var note: String? = null
    var onRouteTap: ((Int) -> Unit)? = null

    fun show(data: RouteMapData?, candidates: List<Candidate>, note: String? = null) {
        this.data = data
        this.candidates = candidates
        this.note = note
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = dp(width)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val bg = Paint().apply { color = 0xFFF1F2F4.toInt() }
    private val river = stroke(0xFFA9CFF2.toInt(), 7f)
    private val roadCasing = mapOf(
        "motorway" to stroke(0xFFE0A84E.toInt(), 6.5f), "trunk" to stroke(0xFFE3C27A.toInt(), 5.5f),
        "primary" to stroke(0xFFD4D7DC.toInt(), 4.5f), "secondary" to stroke(0xFFDADDE1.toInt(), 3.5f),
    )
    private val roadFill = mapOf(
        "motorway" to stroke(0xFFF7C66B.toInt(), 4.5f), "trunk" to stroke(0xFFFBE1A3.toInt(), 3.5f),
        "primary" to stroke(0xFFFFFFFF.toInt(), 2.8f), "secondary" to stroke(0xFFFFFFFF.toInt(), 2f),
    )
    private val casing = stroke(0xFFFFFFFF.toInt(), 13f)
    private val altLine = stroke(0xFF8796AE.toInt(), 8f)
    private val mainCasing = stroke(0xFF0B57C2.toInt(), 13f)
    private val mainLine = stroke(0xFF2F8BFF.toInt(), 9f)
    private val pinFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(3f) }
    private val tagBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = dp(16f); isFakeBoldText = true }
    private val credit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6B7380.toInt(); textSize = dp(12f) }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6B7380.toInt(); textSize = dp(16f) }
    private val path = Path()

    private var project: ((GeoPoint) -> Pair<Float, Float>)? = null

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val d = data
        if (d == null || (d.routes.isEmpty() && d.roads.isEmpty())) {
            canvas.drawText("이 구간은 지도 데이터가 없어요", dp(16f), height / 2f, empty)
            return
        }
        // Fit the routes (not the whole background) with some room for the tags; without routes, the two ends with
        // some surroundings
        val ends = listOf(d.from, d.to)
        val fit = if (d.routes.isNotEmpty()) d.routes.values.flatten() + ends else {
            val span = maxOf(kotlin.math.abs(d.from.lat - d.to.lat), kotlin.math.abs(d.from.lon - d.to.lon), 0.02) * 0.2
            ends.flatMap { listOf(GeoPoint(it.lat - span, it.lon - span), GeoPoint(it.lat + span, it.lon + span)) }
        }
        val lat0 = fit.map { it.lat }.average()
        val kx = GeoPoint.METERS_PER_DEG_LAT * cos(lat0 * PI / 180)
        val ky = GeoPoint.METERS_PER_DEG_LAT
        val minX = fit.minOf { it.lon } * kx
        val maxX = fit.maxOf { it.lon } * kx
        val minY = fit.minOf { it.lat } * ky
        val maxY = fit.maxOf { it.lat } * ky
        val pad = dp(48f)
        val s = min((width - 2 * pad) / max(maxX - minX, 300.0), (height - 2 * pad) / max(maxY - minY, 300.0))
        val cx = width / 2.0 - (minX + maxX) / 2 * s
        val cy = height / 2.0 + (minY + maxY) / 2 * s
        val px = { p: GeoPoint -> (cx + p.lon * kx * s).toFloat() to (cy - p.lat * ky * s).toFloat() }
        project = px

        fun drawLine(pts: List<GeoPoint>, paint: Paint) {
            if (pts.size < 2) return
            path.reset()
            pts.forEachIndexed { i, p ->
                val (x, y) = px(p)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }

        d.rivers.forEach { drawLine(it, river) }
        for (cls in listOf("secondary", "primary", "trunk", "motorway")) {
            val ways = d.roads.filter { it.first == cls }
            ways.forEach { drawLine(it.second, roadCasing.getValue(cls)) }
            ways.forEach { drawLine(it.second, roadFill.getValue(cls)) }
        }
        // Other candidates first, the highlighted one on top
        val ordered = candidates.sortedBy { it.highlighted }
        ordered.forEach { c ->
            val pts = d.routes[c.index] ?: return@forEach
            drawLine(pts, if (c.highlighted) mainCasing else casing)
            drawLine(pts, if (c.highlighted) mainLine else altLine)
        }

        // Pins: start (blue dot) and destination (red)
        // The routes start and end on the road nearest to the places: put the pins where the lines do
        val shown = (candidates.firstOrNull { it.highlighted } ?: candidates.firstOrNull())?.let { d.routes[it.index] }
        val (sx, sy) = px(shown?.first() ?: d.from)
        pinFill.color = 0xFF2F8BFF.toInt()
        canvas.drawCircle(sx, sy, dp(11f), pinFill)
        canvas.drawCircle(sx, sy, dp(11f), pinRing)
        val (ex, ey) = px(shown?.last() ?: d.to)
        pinFill.color = 0xFFE5484D.toInt()
        canvas.drawCircle(ex, ey - dp(16f), dp(13f), pinFill)
        canvas.drawCircle(ex, ey - dp(16f), dp(13f), pinRing)
        path.reset(); path.moveTo(ex - dp(8f), ey - dp(8f)); path.lineTo(ex + dp(8f), ey - dp(8f)); path.lineTo(ex, ey + dp(4f)); path.close()
        canvas.drawPath(path, pinFill)
        canvas.drawCircle(ex, ey - dp(16f), dp(4.5f), pinRing.apply { style = Paint.Style.FILL })
        pinRing.style = Paint.Style.STROKE

        // Tags on each route, at a spot away from the other routes so they point at the right line
        val placed = mutableListOf<RectF>()
        ordered.forEach { c ->
            val pts = d.routes[c.index] ?: return@forEach
            val others = candidates.filter { it.index != c.index }.mapNotNull { d.routes[it.index] }
            val at = tagPoint(pts, others, px)
            val (x, y) = px(at)
            val w = tagText.measureText(c.tag) + dp(20f)
            val h = dp(30f)
            var r = RectF(x - w / 2, y - h - dp(10f), x + w / 2, y - dp(10f))
            if (placed.any { RectF.intersects(it, r) }) r = RectF(r.left, y + dp(10f), r.right, y + dp(10f) + h)
            placed += r
            tagBg.color = if (c.highlighted) 0xFF0B57C2.toInt() else 0xFF3A4352.toInt()
            canvas.drawRoundRect(r, dp(8f), dp(8f), tagBg)
            canvas.drawText(c.tag, r.left + dp(10f), r.bottom - dp(9f), tagText)
        }
        note?.let { n ->
            val w = empty.measureText(n) + dp(20f)
            tagBg.color = 0xE6FFFFFF.toInt()
            canvas.drawRoundRect(RectF(dp(10f), dp(10f), dp(10f) + w, dp(44f)), dp(8f), dp(8f), tagBg)
            canvas.drawText(n, dp(20f), dp(33f), empty)
        }
        val note = "© OpenStreetMap contributors"
        canvas.drawText(note, width - credit.measureText(note) - dp(8f), height - dp(8f), credit)
    }

    /** The point of [pts] farthest from the other routes (in the middle part of the route). */
    private fun tagPoint(pts: List<GeoPoint>, others: List<List<GeoPoint>>, px: (GeoPoint) -> Pair<Float, Float>): GeoPoint {
        val from = pts.size / 5
        val to = max(from + 1, pts.size * 4 / 5)
        var best = pts[pts.size / 2]
        var bestD = -1f
        for (i in from until to) {
            val (x, y) = px(pts[i])
            val dmin = others.flatten().minOfOrNull { o -> px(o).let { (ox, oy) -> hypot(ox - x, oy - y) } } ?: 0f
            if (dmin > bestD) { bestD = dmin; best = pts[i] }
        }
        return best
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val d = data ?: return false
        val px = project ?: return false
        var bestIdx: Int? = null
        var bestD = dp(36f)
        candidates.forEach { c ->
            d.routes[c.index]?.forEach { p ->
                val (x, y) = px(p)
                val dist = hypot(x - e.x, y - e.y)
                if (dist < bestD) { bestD = dist; bestIdx = c.index }
            }
        }
        bestIdx?.let { onRouteTap?.invoke(it) }
        return true
    }
}
