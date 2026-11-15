package com.iclab.steerscore

import android.content.Context
import android.util.Log
import com.iclab.steerscore.core.Cause
import com.iclab.steerscore.core.EventRecord
import com.iclab.steerscore.core.GeoPoint
import com.iclab.steerscore.core.Habit
import com.iclab.steerscore.core.RoadClass
import com.iclab.steerscore.core.Situation
import com.iclab.steerscore.core.TripLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every finished trip's deductions with their place and road type, for the cumulative analysis. Kept as one JSON
 * file in the app's internal storage; nothing leaves the car.
 */
class TripLogStore(context: Context) {

    private val file = File(context.filesDir, FILE)
    private var cache: List<TripLog>? = null

    fun load(): List<TripLog> = cache ?: runCatching {
        if (!file.exists()) return@runCatching emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }.onFailure { Log.w("SteerScore", "trip log unreadable: ${it.message}") }
        .getOrDefault(emptyList())
        .also { cache = it }

    fun add(log: TripLog) = save((load() + log).takeLast(MAX_TRIPS))

    fun addAll(logs: List<TripLog>) = save((load() + logs).takeLast(MAX_TRIPS))

    fun clear() = save(emptyList())

    private fun save(logs: List<TripLog>) {
        cache = logs
        val arr = JSONArray()
        logs.forEach { arr.put(toJson(it)) }
        runCatching { file.writeText(arr.toString()) }
            .onFailure { Log.w("SteerScore", "trip log not saved: ${it.message}") }
    }

    private fun toJson(t: TripLog): JSONObject = JSONObject()
        .put("endedAt", t.endedAt)
        .put("label", t.label)
        .put("distanceKm", t.distanceKm)
        .put("durationMs", t.durationMs)
        .put("baseline", JSONObject(t.baseline))
        .put("steer", JSONObject(t.steer))
        .put("route", JSONArray().apply { t.route.forEach { put(it.lat); put(it.lon) } })
        .put("roadInfo", t.roadInfo)
        .put("gps", t.gps)
        .put("events", JSONArray().apply {
            t.events.forEach { e ->
                put(
                    JSONObject()
                        .put("t", e.offsetMs)
                        .put("rule", e.ruleId ?: JSONObject.NULL)
                        .put("cause", e.cause.name)
                        .put("penalty", e.penalty.toDouble())
                        .put("habits", JSONObject().apply { e.habits.forEach { (h, g) -> put(h.name, g.toDouble()) } })
                        .put("road", e.situation.name)
                        .put("class", e.roadClass?.name ?: JSONObject.NULL)
                        .put("lat", e.point.lat)
                        .put("lon", e.point.lon)
                )
            }
        })

    private fun fromJson(o: JSONObject): TripLog {
        val route = o.getJSONArray("route").let { r -> (0 until r.length() / 2).map { GeoPoint(r.getDouble(2 * it), r.getDouble(2 * it + 1)) } }
        val events = o.getJSONArray("events").let { arr ->
            (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                val habits = e.getJSONObject("habits")
                EventRecord(
                    offsetMs = e.getLong("t"),
                    ruleId = if (e.isNull("rule")) null else e.getString("rule"),
                    cause = Cause.valueOf(e.getString("cause")),
                    penalty = e.getDouble("penalty").toFloat(),
                    habits = habits.keys().asSequence().associate { Habit.valueOf(it) to habits.getDouble(it).toFloat() },
                    situation = Situation.parse(e.getString("road")),
                    point = GeoPoint(e.getDouble("lat"), e.getDouble("lon")),
                    roadClass = e.optString("class").takeIf { it.isNotEmpty() && it != "null" }
                        ?.let { n -> RoadClass.entries.firstOrNull { it.name == n } },
                )
            }
        }
        return TripLog(
            endedAt = o.getLong("endedAt"),
            label = o.getString("label"),
            distanceKm = o.getDouble("distanceKm"),
            durationMs = o.getLong("durationMs"),
            baseline = o.getJSONObject("baseline").toIntMap(),
            steer = o.getJSONObject("steer").toIntMap(),
            events = events,
            route = route,
            roadInfo = o.optBoolean("roadInfo", false),
            gps = o.optBoolean("gps", false),
        )
    }

    private fun JSONObject.toIntMap(): Map<String, Int> = keys().asSequence().associateWith { getInt(it) }

    companion object {
        private const val FILE = "trip_log.json"
        private const val MAX_TRIPS = 200
    }
}
