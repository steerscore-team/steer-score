package com.iclab.steerscore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the app knows about the drive, for answering the driver through Gleo AI. The live drive updates it; Gleo asks
 * through [GleoReceiver]. In-memory: the keep-alive service keeps the process alive during a live drive.
 */
object DriveState {
    data class Deduction(val atMs: Long, val cause: String, val place: String, val road: String?, val points: Float, val advice: String)

    @Volatile var tripActive = false
    @Volatile var tripStartMs = 0L
    @Volatile var baseline = 100
    @Volatile var steer = 100
    @Volatile var ruleLabel = ""
    private val deductions = ArrayDeque<Deduction>()

    @Synchronized fun startTrip(nowMs: Long, ruleLabel: String) {
        tripActive = true
        tripStartMs = nowMs
        baseline = 100
        steer = 100
        this.ruleLabel = ruleLabel
        deductions.clear()
    }

    @Synchronized fun endTrip() { tripActive = false }

    @Synchronized fun add(d: Deduction) {
        deductions.addLast(d)
        while (deductions.size > 50) deductions.removeFirst()
    }

    @Synchronized fun recent(nowMs: Long, withinMs: Long): List<Deduction> = deductions.filter { nowMs - it.atMs <= withinMs }

    @Synchronized fun all(): List<Deduction> = deductions.toList()
}

/**
 * Gleo AI integration (Pleos "Gleo Interface"): the driver asks by voice, Gleo calls one of the functions declared in
 * assets/schema.json with a broadcast, and the app answers within 7 s with APP_ACTION_RESPONSE. Gleo's LLM turns the
 * returned message and data into its spoken reply, so "방금 나 감점됐어?" gets the reason in natural words. The app never
 * starts a conversation itself; at the moment of a deduction it only plays a chime (or a short line, see VoiceCoach).
 */
class GleoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val params = readParams(intent)
        Log.i(TAG, "gleo action $action $params")
        val (status, message, data) = when (action) {
            RECENT -> recent(params.optDouble("minutes", 10.0))
            SUMMARY -> summary(context)
            HABIT -> habit(context)
            else -> return
        }
        val response = JSONObject().apply {
            put("functionName", action)
            put("status", status)
            put("message", message)
            put("mode", "voice")
            put("data", data.toString())
        }
        context.sendBroadcast(Intent(RESPONSE).apply {
            putExtra("packageName", context.packageName)
            putExtra("response", response.toString())
        })
        Log.i(TAG, "gleo response $response")
    }

    /** The docs show both forms: every parameter as its own extra, or one JSON string in ACTION_PARAM. */
    private fun readParams(intent: Intent): JSONObject {
        val extras = intent.extras ?: return JSONObject()
        extras.getString("ACTION_PARAM")?.let { s -> runCatching { return JSONObject(s) } }
        return JSONObject().apply { extras.keySet().forEach { k -> runCatching { put(k, extras.get(k)) } } }
    }

    private fun recent(minutes: Double): Triple<String, String, JSONArray> {
        val now = System.currentTimeMillis()
        val list = DriveState.recent(now, (minutes.coerceIn(1.0, 120.0) * 60_000).toLong())
        val data = JSONArray()
        list.asReversed().take(5).forEach { d ->
            data.put(JSONObject().apply {
                put("minutesAgo", ((now - d.atMs) / 60_000.0).let { Math.round(it * 10) / 10.0 })
                put("deduction", d.cause)
                put("where", d.place + (d.road?.let { " ($it)" } ?: ""))
                put("points", Math.round(d.points * 10) / 10.0)
                put("advice", d.advice)
            })
        }
        val message = when {
            !DriveState.tripActive && list.isEmpty() -> "No drive is being scored right now."
            list.isEmpty() -> "There was no deduction in the last ${minutes.toInt()} minutes; the current SteerScore is ${DriveState.steer}."
            else -> "There ${if (list.size == 1) "was 1 deduction" else "were ${list.size} deductions"} in the last ${minutes.toInt()} minutes; " +
                "the latest was ${list.last().cause} at ${list.last().place}, minus ${"%.1f".format(list.last().points)} points. " +
                "The current SteerScore is ${DriveState.steer}."
        }
        return Triple("success", message, data)
    }

    private fun summary(context: Context): Triple<String, String, JSONArray> {
        val all = DriveState.all()
        if (!DriveState.tripActive && all.isEmpty()) {
            val last = TripLogStore(context).load().maxByOrNull { it.endedAt }
                ?: return Triple("success", "There is no recorded drive yet.", JSONArray())
            val data = JSONArray().put(JSONObject().apply {
                put("trip", last.label); put("distanceKm", Math.round(last.distanceKm * 10) / 10.0)
                put("steerScore", last.steer.values.firstOrNull() ?: 100); put("baselineScore", last.baseline.values.firstOrNull() ?: 100)
            })
            return Triple("success", "The last recorded drive scored ${last.steer.values.firstOrNull() ?: 100} in SteerScore.", data)
        }
        val byCause = all.groupBy { it.cause }.mapValues { (_, v) -> v.size to v.sumOf { it.points.toDouble() } }
            .entries.sortedByDescending { it.value.second }
        val data = JSONArray()
        byCause.forEach { (cause, v) -> data.put(JSONObject().apply { put("deduction", cause); put("times", v.first); put("points", Math.round(v.second * 10) / 10.0) }) }
        val worst = byCause.firstOrNull()
        val message = "The current SteerScore is ${DriveState.steer}, and the speed-only score is ${DriveState.baseline}. " +
            (worst?.let { "The largest loss is ${it.key}, ${it.value.first} times." } ?: "There has been no deduction so far.")
        return Triple("success", message, data)
    }

    private fun habit(context: Context): Triple<String, String, JSONArray> {
        val logs = TripLogStore(context).load()
        if (logs.isEmpty()) return Triple("success", "There are not enough recorded drives to suggest a habit yet.", JSONArray())
        val ins = com.iclab.steerscore.core.Insights.of(logs, com.iclab.steerscore.core.SpeedRuleSets.TMAP.id)
        val data = JSONArray()
        ins.habits.take(3).forEach { h ->
            data.put(JSONObject().apply {
                put("habit", h.habit.goal); put("pointsPerTrip", Math.round(h.perTrip * 10) / 10.0); put("times", h.count)
                h.topSituation?.let { put("mostlyAt", it.label) }
            })
        }
        val goal = ins.goal
        val message = if (goal == null) "No habit has cost points in the recorded drives."
        else "Over ${ins.tripCount} drives, the habit that cost the most is: ${goal.habit.goal}. Keeping it would add about ${"%.1f".format(goal.perTrip)} points per drive."
        return Triple("success", message, data)
    }

    companion object {
        private const val TAG = "SteerScore"
        const val RECENT = "GET_RECENT_DEDUCTIONS"
        const val SUMMARY = "GET_DRIVE_SUMMARY"
        const val HABIT = "GET_HABIT_TO_FIX"
        const val RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    }
}
