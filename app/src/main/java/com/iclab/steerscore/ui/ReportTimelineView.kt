package com.iclab.steerscore.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.iclab.steerscore.R
import com.iclab.steerscore.core.EnforcementZone
import com.iclab.steerscore.core.TripReport
import com.iclab.steerscore.core.ZoneKind
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Whole-trip timeline for the after-trip report: both scores stepping down at each deduction, and every event
 * placed on a steering lane and a speed lane underneath, so the driver sees when and why the score dropped.
 */
class ReportTimelineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var report: TripReport? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Speed enforcement zones, already shifted to offsets from the trip start. */
    var zones: List<EnforcementZone> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private fun color(id: Int) = context.getColor(id)

    private val grid = Paint().apply { color = color(R.color.panel_line); strokeWidth = dp(1f) }
    private val baselineLine = stroke(R.color.text_muted, 3.5f)
    private val steerLine = stroke(R.color.accent, 5f)
    private val laneLine = Paint().apply { color = color(R.color.panel_line); strokeWidth = dp(1.5f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.panel) }
    private val axisText = text(R.color.text_muted, 19f)
    private val laneText = text(R.color.text_muted, 21f)
    private val noteText = text(R.color.text, 20f)
    private val endText = text(R.color.text, 28f).apply { isFakeBoldText = true }
    private val zoneFill = Paint().apply { color = color(R.color.warn); alpha = 30 }
    private val zoneLine = stroke(R.color.warn, 1.5f).apply { pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f) }
    private val zoneText = text(R.color.warn, 18f)
    private val bracket = stroke(R.color.bad, 3f)
    private val path = Path()

    private fun stroke(colorRes: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(colorRes)
        style = Paint.Style.STROKE
        strokeWidth = dp(widthDp)
        strokeJoin = Paint.Join.ROUND
    }

    private fun text(colorRes: Int, sizeDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(colorRes)
        textSize = dp(sizeDp)
    }

    override fun onDraw(canvas: Canvas) {
        val r = report ?: return
        val left = dp(76f)
        val right = width - dp(64f)
        val chartTop = dp(34f)
        val chartBottom = height * 0.56f
        val steerLane = height * 0.70f
        val speedLane = height * 0.84f
        val axisY = height - dp(6f)
        val end = r.durationMs.coerceAtLeast(1L)
        val x = { t: Long -> left + (right - left) * t.coerceIn(0L, end) / end }

        // Score axis: 100 down to just below the lowest score, gridlines every 10 or 20 points
        val minScore = r.steps.minOf { minOf(it.baseline, it.steer) }
        val low = (floor((minScore - 5) / 10.0) * 10).coerceIn(0.0, 90.0).toFloat()
        val y = { s: Int -> chartBottom - (chartBottom - chartTop) * (s - low) / (100f - low) }
        val step = if (100 - low > 50) 20 else 10
        var g = 100
        while (g >= low) {
            canvas.drawLine(left, y(g), right, y(g), grid)
            canvas.drawText("$g", left - dp(44f), y(g) + dp(7f), axisText)
            g -= step
        }

        // Enforcement zones across the score chart
        for (z in zones) {
            val x0 = x(z.startMs)
            if (z.kind == ZoneKind.SECTION) {
                canvas.drawRect(x0, chartTop - dp(10f), x(z.endMs), chartBottom, zoneFill)
            } else {
                canvas.drawLine(x0, chartTop - dp(10f), x0, chartBottom, zoneLine)
            }
            canvas.drawText(z.label, x0 + dp(4f), chartTop - dp(12f), zoneText)
        }

        drawSteps(canvas, r, x, y, end, baseline = true)
        drawSteps(canvas, r, x, y, end, baseline = false)
        val last = r.steps.last()
        val (bY, sY) = y(last.baseline) to y(last.steer)
        // Keep the two end labels apart when the scores are close
        val gap = dp(30f)
        val sYAdj = if (kotlin.math.abs(bY - sY) < gap) bY + gap else sY
        endText.color = color(R.color.text_muted)
        canvas.drawText("${last.baseline}", right + dp(10f), bY + dp(10f), endText)
        endText.color = color(R.color.accent)
        canvas.drawText("${last.steer}", right + dp(10f), sYAdj + dp(10f), endText)

        // Event lanes
        canvas.drawLine(left, steerLane, right, steerLane, laneLine)
        canvas.drawLine(left, speedLane, right, speedLane, laneLine)
        canvas.drawText("조향", dp(8f), steerLane + dp(7f), laneText)
        canvas.drawText("속도", dp(8f), speedLane + dp(7f), laneText)

        for ((t, e) in r.events) {
            val cy = if (e.isSteering) steerLane else speedLane
            val radius = if (e.penalty == 0f) dp(6f) else dp(7f) + dp(1.1f) * e.penalty.coerceAtMost(10f)
            dot.color = color(EventText.colorRes(e))
            canvas.drawCircle(x(t), cy, radius + dp(2f), dotRing)
            canvas.drawCircle(x(t), cy, radius, dot)
        }

        // Notes: each weaving run, the biggest other steering deduction and the biggest speed deduction
        val noteAbove = steerLane - dp(36f)
        // Horizontal spans already used per text row, so notes never overlap
        val taken = mutableMapOf<Float, MutableList<ClosedFloatingPointRange<Float>>>()
        fun note(text: String, cx: Float, ny: Float) {
            val w = noteText.measureText(text)
            val x0 = (cx - w / 2).coerceIn(left, right - w)
            val row = taken.getOrPut(ny) { mutableListOf() }
            if (row.any { x0 < it.endInclusive && x0 + w > it.start }) return
            row += x0..(x0 + w + dp(12f))
            canvas.drawText(text, x0, ny, noteText)
        }
        for (w in r.weaving) {
            val x0 = x(w.startOffsetMs)
            val x1 = x(w.endOffsetMs).coerceAtLeast(x0 + dp(16f))
            val by = steerLane - dp(25f)
            canvas.drawLine(x0, by, x1, by, bracket)
            canvas.drawLine(x0, by, x0, by + dp(5f), bracket)
            canvas.drawLine(x1, by, x1, by + dp(5f), bracket)
            note("칼치기 ${w.count}회 연속 ${penalty(w.penalty)}", (x0 + x1) / 2, noteAbove)
        }
        val inWeaving = { t: Long -> r.weaving.any { t in it.startOffsetMs..it.endOffsetMs } }
        r.events.filter { it.second.isSteering && it.second.penalty > 0f && !inWeaving(it.first) }
            .maxByOrNull { it.second.penalty }
            ?.let { (t, e) -> note("${EventText.title(e)} ${penalty(e.penalty)}", x(t), noteAbove) }
        r.events.filter { !it.second.isSteering && it.second.penalty > 0f }
            .maxByOrNull { it.second.penalty }
            ?.let { (t, e) -> note("${EventText.title(e)} ${penalty(e.penalty)}", x(t), speedLane + dp(40f)) }

        // Time axis
        val tickMs = listOf(30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 1_200_000L).first { end / it <= 7 }
        var tick = 0L
        while (tick <= end) {
            val label = EventText.clock(tick)
            canvas.drawText(label, x(tick) - axisText.measureText(label) / 2, axisY, axisText)
            tick += tickMs
        }
    }

    private fun drawSteps(
        canvas: Canvas, r: TripReport, x: (Long) -> Float, y: (Int) -> Float, end: Long, baseline: Boolean,
    ) {
        path.reset()
        var prev = if (baseline) r.steps.first().baseline else r.steps.first().steer
        path.moveTo(x(0), y(prev))
        for (s in r.steps.drop(1)) {
            val v = if (baseline) s.baseline else s.steer
            if (v == prev) continue
            path.lineTo(x(s.offsetMs), y(prev))
            path.lineTo(x(s.offsetMs), y(v))
            prev = v
        }
        path.lineTo(x(end), y(prev))
        canvas.drawPath(path, if (baseline) baselineLine else steerLine)
    }

    private fun penalty(p: Float) = "-%s".format(Locale.US, if (p == ceil(p)) "%.0f".format(Locale.US, p) else "%.1f".format(Locale.US, p))
}
