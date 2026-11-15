package com.iclab.steerscore.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.iclab.steerscore.R

/** 270° ring with the trip score in the middle; the ring color follows the score band. */
class ScoreRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var score: Int = 100
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var caption: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.panel_line)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = context.getColor(R.color.text)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = context.getColor(R.color.text_muted)
    }
    private val oval = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val stroke = size * 0.08f
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        arc.color = context.getColor(scoreColor(score))
        val inset = stroke / 2 + 4
        oval.set(inset, inset, size - inset, size - inset)

        canvas.drawArc(oval, 135f, 270f, false, track)
        canvas.drawArc(oval, 135f, 270f * score / 100f, false, arc)

        number.textSize = size * 0.32f
        canvas.drawText(score.toString(), size / 2, size / 2 + number.textSize * 0.35f, number)
        label.textSize = size * 0.075f
        canvas.drawText(caption, size / 2, size * 0.78f, label)
    }

    companion object {
        fun scoreColor(score: Int) = when {
            score >= 80 -> R.color.good
            score >= 60 -> R.color.warn
            else -> R.color.bad
        }
    }
}
