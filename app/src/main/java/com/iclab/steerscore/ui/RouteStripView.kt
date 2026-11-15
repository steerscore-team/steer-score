package com.iclab.steerscore.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.iclab.steerscore.R
import com.iclab.steerscore.core.RouteStep
import com.iclab.steerscore.core.Situation
import java.util.Locale
import kotlin.math.max

/**
 * The guided route as a strip from the start (left) to the destination (right), to scale by distance: expressway
 * stretches (IC entry to IC exit) in blue, IC entries / merges / exits / forks / tolls as labelled orange points,
 * intersection turns as small ticks. The navigation gives no route geometry through the SDK, only the turn-by-turn
 * list, so this is a diagram of what the route goes through rather than a map.
 */
class RouteStripView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var steps: List<RouteStep> = emptyList()
    private var totalM = 0.0
    private var destination = ""

    fun show(steps: List<RouteStep>, totalM: Double, destination: String) {
        this.steps = steps
        this.totalM = max(totalM, steps.maxOfOrNull { it.atM } ?: 0.0)
        this.destination = destination
        requestLayout()
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun color(id: Int) = context.getColor(id)

    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A4554.toInt(); strokeWidth = dp(8f); strokeCap = Paint.Cap.ROUND
    }
    private val express = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent); strokeWidth = dp(10f); strokeCap = Paint.Cap.ROUND
    }
    private val point = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.warn) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F141A.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text); textSize = dp(15f) }
    private val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_muted); textSize = dp(14f) }
    private val stem = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_muted); strokeWidth = dp(1f) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(150f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (steps.isEmpty() || totalM <= 0.0) {
            canvas.drawText("내비가 회전 안내 목록을 아직 보내지 않았어요", dp(12f), height / 2f, muted)
            return
        }
        val left = dp(24f)
        val right = width - dp(24f)
        val y = dp(82f)
        val x = { m: Double -> (left + (right - left) * (m / totalM).coerceIn(0.0, 1.0)).toFloat() }

        canvas.drawLine(left, y, right, y, base)
        // Expressway stretches: IC entry to IC exit (or to the end)
        var entry: Double? = if (steps.firstOrNull { it.situation == Situation.IC_ENTRY || it.situation == Situation.IC_EXIT }?.situation == Situation.IC_EXIT) 0.0 else null
        for (s in steps) {
            if (s.situation == Situation.IC_ENTRY && entry == null) entry = s.atM
            if (s.situation == Situation.IC_EXIT) entry?.let { canvas.drawLine(x(it), y, x(s.atM), y, express); entry = null }
        }
        entry?.let { canvas.drawLine(x(it), y, right, y, express) }

        // Intersection turns: small ticks on the line
        steps.filter { it.situation == Situation.INTERSECTION || it.situation == Situation.ROUNDABOUT }.forEach {
            canvas.drawCircle(x(it.atM), y, dp(4f), tick)
        }
        // Expressway entries/exits, merges, forks, tolls: labelled points, labels on two rows so they do not collide
        val rowEnds = floatArrayOf(-1e9f, -1e9f)
        steps.filter { it.situation in LABELLED }.forEach { s ->
            val px = x(s.atM)
            canvas.drawCircle(px, y, dp(8f), point)
            canvas.drawCircle(px, y, dp(8f), ring)
            val text = "${name(s)} ${km(s.atM)}"
            val w = label.measureText(text)
            val lx = (px - w / 2).coerceIn(0f, width - w)
            val row = if (lx > rowEnds[0] + dp(8f)) 0 else if (lx > rowEnds[1] + dp(8f)) 1 else -1
            if (row < 0) return@forEach
            val ly = if (row == 0) y - dp(22f) else y - dp(46f)
            canvas.drawLine(px, y - dp(9f), px, ly + dp(4f), stem)
            canvas.drawText(text, lx, ly, label)
            rowEnds[row] = lx + w
        }

        // Ends and legend
        canvas.drawCircle(left, y, dp(7f), tick)
        canvas.drawCircle(right, y, dp(7f), point)
        canvas.drawText("출발", left - dp(12f), y + dp(30f), muted)
        val end = "도착 · $destination ${km(totalM)}"
        canvas.drawText(end, right - muted.measureText(end) + dp(12f), y + dp(30f), muted)
        val turns = steps.count { it.situation == Situation.INTERSECTION }
        val expressKm = expresswayM() / 1000.0
        val legend = "━ 파랑: 고속도로 %.1f km   ● 주황: 진출입·합류·분기·톨게이트   • 흰 점: 교차로 회전 %d회".format(Locale.US, expressKm, turns)
        canvas.drawText(legend, left - dp(12f), y + dp(58f), muted)
    }

    private fun expresswayM(): Double {
        var sum = 0.0
        var entry: Double? = if (steps.firstOrNull { it.situation == Situation.IC_ENTRY || it.situation == Situation.IC_EXIT }?.situation == Situation.IC_EXIT) 0.0 else null
        for (s in steps) {
            if (s.situation == Situation.IC_ENTRY && entry == null) entry = s.atM
            if (s.situation == Situation.IC_EXIT) entry?.let { sum += s.atM - it; entry = null }
        }
        entry?.let { sum += totalM - it }
        return sum
    }

    /** "북청계IC 진입", "광명역IC 진출", "안양과천톨게이트", else the situation ("분기 구간"). */
    private fun name(s: RouteStep): String {
        val place = s.place
        return when {
            place.isEmpty() -> s.situation!!.label
            s.situation == Situation.IC_ENTRY -> "$place 진입"
            s.situation == Situation.IC_EXIT -> "$place 진출"
            s.situation == Situation.TOLL -> place
            else -> s.situation!!.label
        }
    }

    private fun km(m: Double) = if (m < 1000) "%.0f m".format(Locale.US, m) else "%.1f km".format(Locale.US, m / 1000)

    private companion object {
        val LABELLED = setOf(Situation.IC_ENTRY, Situation.MERGE, Situation.IC_EXIT, Situation.FORK, Situation.TOLL)
    }
}
