package com.iclab.steerscore.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.iclab.steerscore.R
import com.iclab.steerscore.core.AnalyzerConfig
import com.iclab.steerscore.core.EnforcementZone
import com.iclab.steerscore.core.ZoneKind

/**
 * Rolling chart of the last [windowMs] of estimated lateral acceleration (left up) with the speed as a faint
 * second line and a vertical marker for every detected event. This is where a lane change shows its S shape.
 */
class TraceView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private data class Point(val timeMs: Long, val lateral: Float, val speedKmh: Float)
    /** [ruleId] is null for steering events, which show under every baseline. */
    private data class Marker(val timeMs: Long, val label: String, val colorRes: Int, val ruleId: String?)

    /** Only speed events of this baseline rule set are drawn. */
    var ruleFilter: String? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Speed enforcement zones of the scenario being replayed, on the same time axis as the samples. */
    var zones: List<EnforcementZone> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var windowMs = 30_000L
    private val points = ArrayDeque<Point>()
    private val markers = ArrayDeque<Marker>()
    private val cfg = AnalyzerConfig()

    private val axis = Paint().apply { color = context.getColor(R.color.panel_line); strokeWidth = 2f }
    private val threshold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.bad)
        alpha = 140
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val lateralLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
    }
    private val speedLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_muted)
        alpha = 110
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val markerLine = Paint().apply { strokeWidth = 3f }
    private val markerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        color = context.getColor(R.color.text_muted)
    }
    private val zoneFill = Paint().apply {
        color = context.getColor(R.color.warn)
        alpha = 34
    }
    private val zoneLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.warn)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val zoneText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        color = context.getColor(R.color.warn)
    }
    private val path = Path()

    fun add(timeMs: Long, lateral: Float, speedKmh: Float) {
        points.addLast(Point(timeMs, lateral, speedKmh))
        while (points.isNotEmpty() && points.first().timeMs < timeMs - windowMs) points.removeFirst()
        while (markers.isNotEmpty() && markers.first().timeMs < timeMs - windowMs) markers.removeFirst()
        invalidate()
    }

    fun mark(timeMs: Long, label: String, colorRes: Int, ruleId: String?) {
        markers.addLast(Marker(timeMs, label, colorRes, ruleId))
        invalidate()
    }

    fun clear() {
        points.clear()
        markers.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = h / 2
        val now = points.lastOrNull()?.timeMs ?: return
        val x = { t: Long -> w - (now - t).toFloat() / windowMs * w }
        // Left is negative lateral acceleration, drawn upward
        val y = { a: Float -> mid + (a / MAX_LATERAL).coerceIn(-1f, 1f) * (h / 2 - 8) }

        for (z in zones) {
            val x0 = x(z.startMs)
            val x1 = x(z.endMs)
            if (x1 < 0f || x0 > w) continue
            if (z.kind == ZoneKind.SECTION) {
                canvas.drawRect(x0.coerceAtLeast(0f), 0f, x1.coerceAtMost(w), h, zoneFill)
                canvas.drawText(z.label, x0.coerceAtLeast(0f) + 8f, h - 40f, zoneText)
            } else {
                canvas.drawLine(x0, 0f, x0, h, zoneLine)
                canvas.drawText(z.label, x0 + 8f, h - 40f, zoneText)
            }
        }

        canvas.drawLine(0f, mid, w, mid, axis)
        for (limit in listOf(cfg.abruptLateral, -cfg.abruptLateral)) {
            canvas.drawLine(0f, y(limit), w, y(limit), threshold)
        }
        canvas.drawText("왼쪽", 8f, 26f, axisText)
        canvas.drawText("오른쪽", 8f, h - 10f, axisText)
        canvas.drawText("급차선변경 기준 ±${cfg.abruptLateral} m/s²", w - 300f, y(-cfg.abruptLateral) - 8f, axisText)

        path.reset()
        points.forEachIndexed { i, p ->
            val py = h - (p.speedKmh / MAX_SPEED).coerceIn(0f, 1f) * h
            if (i == 0) path.moveTo(x(p.timeMs), py) else path.lineTo(x(p.timeMs), py)
        }
        canvas.drawPath(path, speedLine)

        path.reset()
        points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(x(p.timeMs), y(p.lateral)) else path.lineTo(x(p.timeMs), y(p.lateral))
        }
        canvas.drawPath(path, lateralLine)

        markers.filter { it.ruleId == null || it.ruleId == ruleFilter }.forEachIndexed { i, m ->
            val mx = x(m.timeMs)
            val color = context.getColor(m.colorRes)
            markerLine.color = color
            markerText.color = color
            canvas.drawLine(mx, 0f, mx, h, markerLine)
            // Stagger labels over a few rows so events close together stay readable
            canvas.drawText(m.label, mx + 6f, 52f + (i % LABEL_ROWS) * 28f, markerText)
        }
    }

    companion object {
        private const val MAX_LATERAL = 5f
        private const val MAX_SPEED = 160f
        private const val LABEL_ROWS = 4
    }
}
