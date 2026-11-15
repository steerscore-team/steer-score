package com.iclab.steerscore.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.iclab.steerscore.R

/**
 * One signal over the last minutes of the drive, for the driver's screen: speed (with the speed limit as a dashed
 * line) or steering angle (left up, right down). Dots mark the deductions of that kind where they happened.
 */
class SeriesView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    class Point(val timeMs: Long, val value: Float, val limit: Float? = null)
    class Dot(val timeMs: Long, val color: Int)

    private var points: List<Point> = emptyList()
    private var dots: List<Dot> = emptyList()
    /** Fixed y range; [symmetric] centres it on zero (steering). */
    var yMin = 0f
    var yMax = 120f
    var symmetric = false
    var unit = ""
    var upLabel: String? = null
    var downLabel: String? = null
    var lineColor = context.getColor(R.color.accent)
    /** Shown window: the last [windowMs] (null: the whole drive). */
    var windowMs: Long? = 120_000L

    fun update(points: List<Point>, dots: List<Dot>) {
        this.points = points
        this.dots = dots
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeJoin = Paint.Join.ROUND }
    private val limitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f); color = context.getColor(R.color.bad)
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
    }
    private val grid = Paint().apply { color = context.getColor(R.color.panel_line); strokeWidth = dp(1f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_muted); textSize = dp(16f) }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val left = dp(42f); val right = width - dp(8f); val top = dp(8f); val bottom = height - dp(24f)
        val last = points.lastOrNull()?.timeMs ?: 0L
        val first = points.firstOrNull()?.timeMs ?: 0L
        val span = windowMs ?: maxOf(last - first, 60_000L)
        val t0 = if (windowMs == null) first else maxOf(first, last - span)
        var lo = yMin
        var hi = yMax
        if (symmetric) {
            // Fixed ±yMax so lane changes stay readable; a turn's larger wheel angle is drawn at the edge
            lo = -yMax; hi = yMax
        } else {
            hi = maxOf(yMax, points.filter { it.timeMs >= t0 }.maxOfOrNull { maxOf(it.value, it.limit ?: 0f) } ?: 0f)
        }
        fun x(t: Long) = left + (right - left) * ((t - t0).toFloat() / span)
        fun y(v: Float) = bottom - (bottom - top) * ((v.coerceIn(lo, hi) - lo) / (hi - lo))
        for (v in if (symmetric) listOf(hi, 0f, lo) else listOf(hi, (hi + lo) / 2, lo)) {
            canvas.drawLine(left, y(v), right, y(v), grid)
            canvas.drawText("%.0f".format(v), 0f, y(v) + dp(5f), muted)
        }
        upLabel?.let { canvas.drawText(it, left + dp(6f), top + dp(14f), muted) }
        downLabel?.let { canvas.drawText(it, left + dp(6f), bottom - dp(6f), muted) }
        val axis = if (windowMs == null) "주행 전체" else "최근 ${span / 60_000}분"
        canvas.drawText(axis, right - muted.measureText(axis), height - dp(4f), muted)
        val shown = points.filter { it.timeMs >= t0 }
        if (shown.size < 2) return
        // Speed limit: a dashed step line where the navigation knows it
        path.reset()
        var open = false
        for (p in shown) {
            val l = p.limit
            if (l == null) { open = false; continue }
            if (!open) path.moveTo(x(p.timeMs), y(l)) else path.lineTo(x(p.timeMs), y(l))
            open = true
        }
        canvas.drawPath(path, limitPaint)
        line.color = lineColor
        path.reset()
        shown.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.timeMs), y(p.value)) else path.lineTo(x(p.timeMs), y(p.value)) }
        canvas.drawPath(path, line)
        for (d in dots) {
            if (d.timeMs < t0) continue
            val p = shown.lastOrNull { it.timeMs <= d.timeMs } ?: continue
            fill.color = d.color
            canvas.drawCircle(x(d.timeMs), y(p.value), dp(6f), fill)
        }
    }
}
