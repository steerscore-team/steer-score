package com.iclab.steerscore.ui

import com.iclab.steerscore.R
import com.iclab.steerscore.core.Conditions
import com.iclab.steerscore.core.DrivingEvent
import com.iclab.steerscore.core.HardAcceleration
import com.iclab.steerscore.core.HardBraking
import com.iclab.steerscore.core.Cornering
import com.iclab.steerscore.core.IntersectionTurn
import com.iclab.steerscore.core.LaneChange
import com.iclab.steerscore.core.LaneDeparture
import com.iclab.steerscore.core.Speeding
import com.iclab.steerscore.core.Turn
import java.util.Locale
import kotlin.math.abs

/** Korean labels for events, shared by the event list and the chart markers. */
object EventText {

    fun title(e: DrivingEvent): String = when (e) {
        is LaneChange -> {
            val where = if (e.onCurve) "커브 중 " else ""
            when {
                e.abrupt && !e.signaled -> "${where}급차선변경 + 깜빡이 미사용"
                e.abrupt -> "${where}급차선변경"
                !e.signaled -> "${where}깜빡이 미사용 차선변경"
                else -> "${where}차선변경 (정상)"
            }
        }
        is Cornering -> if (e.hard) "급커브 주행" else "커브 (정상)"
        is LaneDeparture -> "차선 이탈 후 복귀"
        is IntersectionTurn -> {
            val name = turnName(e)
            when {
                e.hard && !e.signaled -> "급$name + 깜빡이 미사용"
                e.hard -> "급$name"
                !e.signaled -> "$name: 깜빡이 미사용"
                else -> "$name (정상)"
            }
        }
        is HardAcceleration -> "급가속"
        is HardBraking -> "급감속"
        is Speeding -> "과속"
    }

    /** Short label for the chart marker. */
    fun shortLabel(e: DrivingEvent): String = when (e) {
        is LaneChange -> when {
            e.abrupt && !e.signaled -> "급차선+무깜빡이"
            e.abrupt -> "급차선"
            !e.signaled -> "무깜빡이"
            else -> "차선변경 ✓"
        }
        is Cornering -> if (e.hard) "급커브" else "커브"
        is LaneDeparture -> "차선 이탈"
        is IntersectionTurn -> turnName(e) + if (e.penalty == 0f) " ✓" else if (!e.signaled) " 무깜빡이" else ""
        else -> title(e)
    }

    private fun turnName(e: IntersectionTurn) = when {
        e.uTurn -> "유턴"
        e.direction == Turn.LEFT -> "좌회전"
        else -> "우회전"
    }

    /** Which score the event belongs to: the speed-only baseline, or the steering rules SteerScore adds. */
    fun tag(e: DrivingEvent): String = if (e.isSteering) "조향" else "기존"

    fun detail(e: DrivingEvent): String {
        val body = when (e) {
            is LaneChange -> {
                val dir = if (e.direction == Turn.LEFT) "왼쪽" else "오른쪽"
                "$dir · %.1f초 · 옆 %.1f m · 최대 %.1f m/s²".format(
                    Locale.US, e.durationMs / 1000f, e.lateralOffsetM, e.peakLateral,
                )
            }
            is Cornering -> {
                val dir = if (e.direction == Turn.LEFT) "왼쪽" else "오른쪽"
                "$dir · %.0f초 · %.0f° 회전 · 최대 %.1f m/s²".format(
                    Locale.US, e.durationMs / 1000f, abs(e.headingChangeDeg), e.peakLateral,
                )
            }
            is LaneDeparture -> {
                val dir = if (e.direction == Turn.LEFT) "왼쪽" else "오른쪽"
                "$dir · %.1f초 · 최대 옆 %.1f m 나갔다 복귀".format(Locale.US, e.durationMs / 1000f, e.peakOffsetM)
            }
            is IntersectionTurn -> "%.0f° 회전 · %.1f초 · 최대 %.1f m/s²".format(
                Locale.US, abs(e.headingChangeDeg), e.durationMs / 1000f, e.peakLateral,
            )
            is HardAcceleration -> "%d초간 +%.0f km/h".format(Locale.US, e.windowMs / 1000, e.deltaKmh)
            is HardBraking -> "%d초간 -%.0f km/h".format(Locale.US, e.windowMs / 1000, e.deltaKmh)
            is Speeding -> "제한속도 +%.0f km/h".format(Locale.US, e.overKmh)
        }
        if (e.multiplier == 1f || e.penalty == 0f) return body
        val why = if (e.isSteering) conditionsLabel(e.conditions) else "야간"
        return "$body · $why ×%.2f".format(Locale.US, e.multiplier)
    }

    fun colorRes(e: DrivingEvent): Int = when {
        e.penalty == 0f -> R.color.good
        e.penalty >= 6f -> R.color.bad
        else -> R.color.warn
    }

    fun conditionsLabel(c: Conditions): String = if (c.lowBeam) "야간" else "주간"

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(Locale.US, s / 60, s % 60)
    }
}
