package com.iclab.steerscore.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.iclab.steerscore.R

/**
 * Both scores over the drive so far: SteerScore (speed + steering, blue) and the baseline (speed only, grey). The gap
 * between the lines is what the baseline misses. Dots mark each deduction (red steering, orange speed).
 */
class LiveScoreView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    data class Point(val timeMs: Long, val baseline: Int, val steer: Int)
    data class Dot(val timeMs: Long, val steering: Boolean)

    private var points: List<Point> = emptyList()
    private var dots: List<Dot> = emptyList()
    /** The drive's length when known (a recorded drive), else the axis grows with the drive. */
    var totalMs: Long? = null

    fun update(points: List<Point>, dots: List<Dot>) {
        this.points = points
        this.dots = dots
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeJoin = Paint.Join.ROUND }
    private val grid = Paint().apply { color = context.getColor(R.color.panel_line); strokeWidth = dp(1f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_muted); textSize = dp(13f) }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(16f); isFakeBoldText = true }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val left = dp(34f); val right = width - dp(44f); val top = dp(10f); val bottom = height - dp(22f)
        val low = (points.minOfOrNull { minOf(it.baseline, it.steer) } ?: 100).let { if (it >= 80) 60 else ((it - 10) / 10) * 10 }.coerceAtLeast(0)
        val t0 = points.firstOrNull()?.timeMs ?: 0L
        val span = maxOf(totalMs ?: 0L, (points.lastOrNull()?.timeMs ?: 0L) - t0, 60_000L).toFloat()
        fun x(t: Long) = left + (right - left) * ((t - t0) / span)
        fun y(s: Int) = bottom - (bottom - top) * ((s - low) / (100f - low))
        for (s in listOf(100, (100 + low) / 2, low)) {
            canvas.drawLine(left, y(s), right, y(s), grid)
            canvas.drawText(s.toString(), 0f, y(s) + dp(5f), muted)
        }
        canvas.drawText("0:00", left, height - dp(4f), muted)
        val endLabel = EventText.clock(span.toLong())
        canvas.drawText(endLabel, right - muted.measureText(endLabel), height - dp(4f), muted)
        if (points.isEmpty()) return
        for ((c, pick) in listOf(context.getColor(R.color.text_muted) to { p: Point -> p.baseline },
            context.getColor(R.color.accent) to { p: Point -> p.steer })) {
            line.color = c
            path.reset()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.timeMs), y(pick(p))) else path.lineTo(x(p.timeMs), y(pick(p))) }
            canvas.drawPath(path, line)
        }
        for (d in dots) {
            val p = points.lastOrNull { it.timeMs <= d.timeMs } ?: points.first()
            fill.color = context.getColor(if (d.steering) R.color.bad else R.color.warn)
            canvas.drawCircle(x(d.timeMs), y(p.steer), dp(5f), fill)
        }
        val last = points.last()
        value.color = context.getColor(R.color.accent)
        canvas.drawText(last.steer.toString(), x(last.timeMs) + dp(6f), y(last.steer) + dp(6f), value)
        if (last.baseline != last.steer) {
            value.color = context.getColor(R.color.text_muted)
            canvas.drawText(last.baseline.toString(), x(last.timeMs) + dp(6f), y(last.baseline) + dp(6f), value)
        }
    }
}
