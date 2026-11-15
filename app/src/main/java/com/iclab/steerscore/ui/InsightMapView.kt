package com.iclab.steerscore.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.iclab.steerscore.R
import com.iclab.steerscore.core.GeoPoint
import com.iclab.steerscore.core.MapArea
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cumulative map: every route driven in the area as a grey line and every hotspot as a circle sized by the points
 * lost there (red for steering, orange for speed). There is no basemap; routes and spots are placed by their
 * coordinates on a flat projection, with a scale bar and north up.
 */
class InsightMapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** The map shown: one area of the cumulative analysis. */
    var area: MapArea? = null
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun color(id: Int) = context.getColor(id)

    private val bg = Paint().apply { color = 0xFF0F141A.toInt() }
    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC3DA9FC.toInt()   // the driven routes in the app's blue, over the grey road map
        style = Paint.Style.STROKE
        strokeWidth = dp(5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val startDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A96A3.toInt() }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFF0F141A.toInt()
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.text); textSize = dp(19f) }
    private val labelBg = Paint().apply { color = 0xCC0B0F14.toInt() }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.bg)
        textSize = dp(16f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.text_muted); textSize = dp(16f) }
    private val scale = Paint().apply { color = color(R.color.text_muted); strokeWidth = dp(2f) }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val ins = area
        val points = ins?.routes?.flatten().orEmpty() + ins?.hotspots?.map { it.center }.orEmpty()
        if (ins == null || points.isEmpty()) {
            canvas.drawText("실제 위치(GPS)로 기록한 주행이 쌓이면 도로 지도 위에 경로와 감점 지점이 나와요", dp(20f), height / 2f, muted)
            return
        }

        // Fit the area into the view, same scale on both axes (metres), north up
        val lat0 = points.map { it.lat }.average()
        val kx = GeoPoint.METERS_PER_DEG_LAT * cos(lat0 * PI / 180)
        val ky = GeoPoint.METERS_PER_DEG_LAT
        val minX = points.minOf { it.lon } * kx
        val maxX = points.maxOf { it.lon } * kx
        val minY = points.minOf { it.lat } * ky
        val maxY = points.maxOf { it.lat } * ky
        val pad = dp(56f)
        val spanM = max(max(maxX - minX, maxY - minY), 200.0)
        // At least 200 m across, so a single spot (one stop, or a trip without movement) keeps a sane scale bar
        val s = min(
            (width - 2 * pad) / max(max(maxX - minX, spanM * 0.2), MIN_SPAN_M),
            (height - 2 * pad) / max(max(maxY - minY, spanM * 0.2), MIN_SPAN_M),
        )
        val cx = width / 2f - ((minX + maxX) / 2 * s).toFloat()
        val cy = height / 2f + ((minY + maxY) / 2 * s).toFloat()
        val px = { p: GeoPoint -> cx + (p.lon * kx * s).toFloat() }
        val py = { p: GeoPoint -> cy - (p.lat * ky * s).toFloat() }

        // A map of real-road trips gets the OpenStreetMap roads underneath (virtual scenario roads do not)
        if (ins.gps) com.iclab.steerscore.core.RouteMaps.roadsAround(ins.routes.flatten())?.let { bg ->
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            fun draw(pts: List<GeoPoint>) {
                if (pts.size < 2) return
                path.reset()
                path.moveTo(px(pts[0]), py(pts[0]))
                pts.drop(1).forEach { path.lineTo(px(it), py(it)) }
                canvas.drawPath(path, line)
            }
            line.color = 0xFF1C3550.toInt(); line.strokeWidth = dp(4f)
            bg.rivers.forEach(::draw)
            for ((cls, c, w) in listOf(Triple("primary", 0xFF2A333E.toInt(), 2.5f), Triple("trunk", 0xFF36404D.toInt(), 3.5f),
                Triple("motorway", 0xFF4A4436.toInt(), 4.5f))) {
                line.color = c; line.strokeWidth = dp(w)
                bg.roads.filter { it.first == cls }.forEach { draw(it.second) }
            }
        }
        // Not on the real map (a virtual scenario road, or a path dead-reckoned without GPS): draw the drives as a road of
        // their own (a grey band with a dashed centre line), so the shape reads as a road without claiming a real place
        if (!ins.gps) {
            val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            for ((color, width, dash) in listOf(Triple(0xFF3A4452.toInt(), 26f, false), Triple(0xFF262E38.toInt(), 22f, false),
                Triple(0xFF6B7686.toInt(), 1.5f, true))) {
                band.color = color
                band.strokeWidth = dp(width)
                band.pathEffect = if (dash) android.graphics.DashPathEffect(floatArrayOf(dp(10f), dp(10f)), 0f) else null
                for (r in ins.routes) {
                    if (r.size < 2) continue
                    path.reset()
                    path.moveTo(px(r[0]), py(r[0]))
                    r.drop(1).forEach { path.lineTo(px(it), py(it)) }
                    canvas.drawPath(path, band)
                }
            }
        }
        for (r in ins.routes) {
            if (r.size < 2) continue
            path.reset()
            path.moveTo(px(r[0]), py(r[0]))
            r.drop(1).forEach { path.lineTo(px(it), py(it)) }
            canvas.drawPath(path, road)
            canvas.drawCircle(px(r[0]), py(r[0]), dp(5f), startDot)
        }

        val maxPenalty = ins.hotspots.maxOfOrNull { it.penalty } ?: 1f
        // Small spots first so big ones are drawn on top
        for (h in ins.hotspots.asReversed()) {
            val c = color(if (h.steering) R.color.bad else R.color.warn)
            val r = dp(7f) + dp(16f) * sqrt(h.penalty / maxPenalty)
            halo.color = c
            halo.alpha = 70
            spot.color = c
            canvas.drawCircle(px(h.center), py(h.center), r * 1.7f, halo)
            canvas.drawCircle(px(h.center), py(h.center), r, spot)
            canvas.drawCircle(px(h.center), py(h.center), r, ring)
        }

        // Number and describe the three worst spots
        ins.hotspots.take(3).forEachIndexed { i, h ->
            val x = px(h.center)
            val y = py(h.center)
            canvas.drawText("${i + 1}", x, y + dp(6f), badge)
            // The number is repeated in the label: a label flipped to the left of its spot can sit next to another spot
            val text = "${i + 1}. ${h.place} · ${h.topCause.label} ${h.count}회"
            val w = label.measureText(text)
            val r = dp(7f) + dp(16f) * sqrt(h.penalty / maxPenalty)
            val lx = if (x + r * 1.8f + w + dp(8f) < width) x + r * 1.8f else x - r * 1.8f - w
            val ly = y + dp(7f) + i * 0f
            canvas.drawRect(lx - dp(6f), ly - dp(20f), lx + w + dp(6f), ly + dp(7f), labelBg)
            canvas.drawText(text, lx, ly, label)
        }

        // Scale bar: a round distance about a fifth of the width
        val target = width / 5f / s
        val meters = listOf(50, 100, 200, 500, 1000, 2000, 5000, 10_000).lastOrNull { it <= target } ?: 50
        val len = (meters * s).toFloat()
        val by = height - dp(22f)
        canvas.drawLine(dp(20f), by, dp(20f) + len, by, scale)
        canvas.drawLine(dp(20f), by - dp(6f), dp(20f), by, scale)
        canvas.drawLine(dp(20f) + len, by - dp(6f), dp(20f) + len, by, scale)
        canvas.drawText(if (meters >= 1000) "${meters / 1000} km" else "$meters m", dp(20f) + len + dp(8f), by + dp(5f), muted)
        canvas.drawText("N ↑", width - dp(52f), dp(28f), muted)
        val note = if (ins.gps) "지도 데이터 © OpenStreetMap contributors" else "실제 지도 위치 아님 · GPS 없이 속도와 핸들로 그린 주행 모양"
        canvas.drawText(note, width - muted.measureText(note) - dp(16f), height - dp(16f), muted)
    }

    private companion object {
        /** Smallest area the map shows, in metres. */
        const val MIN_SPAN_M = 200.0
    }
}
