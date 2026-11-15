package com.iclab.steerscore.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.iclab.steerscore.R

/** A steering wheel drawn rotated by the current steering angle (left negative, so negative turns counter-clockwise). */
class SteeringView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var angleDeg: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.text)
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.accent)
    }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.panel_line) }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = size * 0.42f
        rim.strokeWidth = size * 0.07f
        marker.strokeWidth = size * 0.07f

        canvas.save()
        canvas.rotate(angleDeg, cx, cy)
        canvas.drawCircle(cx, cy, r, rim)
        // Three spokes and a top-dead-center marker
        rim.strokeWidth = size * 0.05f
        canvas.drawLine(cx - r, cy, cx + r, cy, rim)
        canvas.drawLine(cx, cy, cx, cy + r, rim)
        canvas.drawCircle(cx, cy, size * 0.12f, hub)
        canvas.drawLine(cx, cy - r - size * 0.03f, cx, cy - r + size * 0.06f, marker)
        canvas.restore()
    }
}
