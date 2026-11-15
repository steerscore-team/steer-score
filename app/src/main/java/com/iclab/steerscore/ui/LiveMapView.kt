package com.iclab.steerscore.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.iclab.steerscore.R
import com.iclab.steerscore.core.GeoPoint
import com.iclab.steerscore.core.RouteMapData
import kotlin.math.atan2
import kotlin.math.cos

/**
 * The drive as it happens (시연 모드): the car moving along its route over the real road map, the part already
 * driven in blue, and a numbered mark where each deduction happened. North up, the camera follows the car.
 * Road map: bundled OpenStreetMap data (© OpenStreetMap contributors), drawn only when the path lies on real roads.
 */
class LiveMapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    data class Mark(val point: GeoPoint, val number: Int, val steering: Boolean)

    private var planned: List<GeoPoint> = emptyList()
    private var driven: List<GeoPoint> = emptyList()
    private var marks: List<Mark> = emptyList()
    private var roads: RouteMapData? = null
    private var lat0 = 0.0
    private var kx = 1.0
    /** Metres shown across the view. */
    var spanM = 2200.0
    /**
     * The position is estimated from speed and steering (no moving GPS fix, e.g. the emulator): the path is not on the
     * real roads, so the road map is left out and the path is dashed, with a note.
     */
    var estimated = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** A new drive: the route it will take (when known in advance) and the road map around it. */
    fun start(route: List<GeoPoint>, roads: RouteMapData?) {
        planned = route
        this.roads = roads
        driven = emptyList()
        marks = emptyList()
        invalidate()
    }

    fun update(driven: List<GeoPoint>, marks: List<Mark>) {
        this.driven = driven
        this.marks = marks
        invalidate()
    }

    private val path = Path()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = dp(15f); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_muted); textSize = dp(16f) }

    // Camera: the car's position, in metres east/north of the first point
    private var cx = 0.0
    private var cy = 0.0
    private var scale = 1.0
    private fun mx(p: GeoPoint) = (p.lon - (planned.firstOrNull() ?: driven.first()).lon) * kx
    private fun my(p: GeoPoint) = (p.lat - (planned.firstOrNull() ?: driven.first()).lat) * 111_320.0
    private fun sx(p: GeoPoint) = (width / 2 + (mx(p) - cx) * scale).toFloat()
    private fun sy(p: GeoPoint) = (height / 2 - (my(p) - cy) * scale).toFloat()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF0F141A.toInt())
        val car = driven.lastOrNull() ?: planned.firstOrNull()
        if (car == null) {
            canvas.drawText("주행을 시작하면 지도에 경로가 그려져요", dp(20f), height / 2f, muted)
            return
        }
        lat0 = car.lat
        kx = 111_320.0 * cos(Math.toRadians(lat0))
        scale = width / spanM
        cx = mx(car)
        cy = my(car)

        fun draw(pts: List<GeoPoint>) {
            if (pts.size < 2) return
            path.reset()
            pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(sx(p), sy(p)) else path.lineTo(sx(p), sy(p)) }
            canvas.drawPath(path, line)
        }
        // Real road map underneath (not under an estimated path: it would look like it ran through fields)
        roads?.takeIf { !estimated }?.let { r ->
            line.color = 0xFF1C3550.toInt(); line.strokeWidth = dp(5f)
            r.rivers.forEach(::draw)
            for ((cls, c, w) in listOf(Triple("primary", 0xFF2A333E.toInt(), 3f), Triple("trunk", 0xFF36404D.toInt(), 4.5f),
                Triple("motorway", 0xFF4A4436.toInt(), 6f))) {
                line.color = c; line.strokeWidth = dp(w)
                r.roads.filter { it.first == cls }.forEach { draw(it.second) }
            }
        }
        // The route ahead, then the part driven
        line.color = 0xFF5A6E84.toInt(); line.strokeWidth = dp(9f)
        draw(planned)
        line.color = context.getColor(R.color.accent); line.strokeWidth = dp(7f)
        if (estimated) line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(14f), dp(10f)), 0f)
        draw(driven)
        line.pathEffect = null
        // Deductions: red for steering, orange for speed, numbered in order
        for (m in marks) {
            val x = sx(m.point); val y = sy(m.point)
            fill.color = context.getColor(if (m.steering) R.color.bad else R.color.warn)
            fill.alpha = 70
            canvas.drawCircle(x, y, dp(22f), fill)
            fill.alpha = 255
            canvas.drawCircle(x, y, dp(14f), fill)
            canvas.drawText(m.number.toString(), x, y + dp(5f), label)
        }
        // The car: heading from the last few metres
        val x = sx(car); val y = sy(car)
        val prev = driven.asReversed().firstOrNull { it.distanceM(car) > 8 }
        val heading = if (prev == null) 0.0 else atan2(mx(car) - mx(prev), my(car) - my(prev))
        fill.color = 0x553DA9FC; canvas.drawCircle(x, y, dp(26f), fill)
        fill.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(x, y, dp(14f), fill)
        canvas.save()
        canvas.rotate(Math.toDegrees(heading).toFloat(), x, y)
        path.reset()
        path.moveTo(x, y - dp(11f)); path.lineTo(x + dp(8f), y + dp(8f)); path.lineTo(x, y + dp(4f)); path.lineTo(x - dp(8f), y + dp(8f)); path.close()
        fill.color = context.getColor(R.color.accent); canvas.drawPath(path, fill)
        canvas.restore()

        canvas.drawText("N ↑", width - dp(44f), dp(28f), muted)
        if (estimated) {
            canvas.drawText("GPS 위치 없음 · 속도와 핸들 각도로 추정한 경로 (실제 도로와 다를 수 있음)", dp(12f), height - dp(12f), muted)
        } else if (roads != null) {
            val credit = "© OpenStreetMap contributors"
            canvas.drawText(credit, width - muted.measureText(credit) - dp(12f), height - dp(12f), muted)
        }
    }
}
