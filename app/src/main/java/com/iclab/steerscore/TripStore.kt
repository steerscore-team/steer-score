package com.iclab.steerscore

import android.content.Context
import com.iclab.steerscore.core.DrivingSample
import com.iclab.steerscore.core.SampleCsv
import com.iclab.steerscore.core.TripAnalyzer
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One finished trip with both scores under every baseline rule set, so the baseline can be switched later. */
data class TripSummary(
    val endedAt: Long,
    /** Scenario title for replays, or "실주행" for live drives. */
    val label: String,
    val distanceKm: Double,
    val durationMs: Long,
    val laneChanges: Int,
    val baseline: Map<String, Int>,
    val steer: Map<String, Int>,
)

/** Keeps recent trip summaries in SharedPreferences. */
class TripHistory(context: Context) {

    private val prefs = context.getSharedPreferences("trips", Context.MODE_PRIVATE)

    fun load(): List<TripSummary> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TripSummary(
                endedAt = o.getLong("endedAt"),
                label = o.getString("label"),
                distanceKm = o.getDouble("distanceKm"),
                durationMs = o.getLong("durationMs"),
                laneChanges = o.getInt("laneChanges"),
                baseline = o.getJSONObject("baseline").toIntMap(),
                steer = o.getJSONObject("steer").toIntMap(),
            )
        }
    }.getOrDefault(emptyList())

    /** Most recent trip per label, newest first: the "same route" comparison table. */
    fun latestPerLabel(): List<TripSummary> = load().distinctBy { it.label }

    fun add(analyzer: TripAnalyzer, label: String) {
        val summary = TripSummary(
            endedAt = System.currentTimeMillis(),
            label = label,
            distanceKm = analyzer.distanceKm,
            durationMs = analyzer.durationMs,
            laneChanges = analyzer.laneChangeCount,
            baseline = analyzer.ruleSets.associate { it.id to analyzer.baselineScore(it.id) },
            steer = analyzer.ruleSets.associate { it.id to analyzer.steerScore(it.id) },
        )
        val arr = JSONArray()
        (listOf(summary) + load()).take(MAX_TRIPS).forEach { t ->
            arr.put(
                JSONObject()
                    .put("endedAt", t.endedAt)
                    .put("label", t.label)
                    .put("distanceKm", t.distanceKm)
                    .put("durationMs", t.durationMs)
                    .put("laneChanges", t.laneChanges)
                    .put("baseline", JSONObject(t.baseline))
                    .put("steer", JSONObject(t.steer))
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun JSONObject.toIntMap(): Map<String, Int> = keys().asSequence().associateWith { getInt(it) }

    companion object {
        // v2: summaries carry per-baseline scores. v3: the A/B scenarios became the ①–④ matrix,
        // so old labels would otherwise linger in the comparison table.
        private const val KEY = "history_v3"
        private const val MAX_TRIPS = 20
    }
}

/**
 * Writes live samples to a CSV in the app's internal files dir so a real drive can be replayed later.
 * AAOS runs apps as user 10 and blocks adb from external app storage, so read it from a debug build with
 * `adb shell run-as com.iclab.steerscore --user 10 cat files/<name>.csv`.
 */
class TripRecorder(context: Context) {

    private val dir: File? = context.filesDir
    private var writer: BufferedWriter? = null

    var file: File? = null
        private set

    fun start() {
        val d = dir ?: return
        val name = "trip_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        file = File(d, name)
        writer = file!!.bufferedWriter().apply {
            write(SampleCsv.HEADER)
            newLine()
        }
    }

    fun write(s: DrivingSample) {
        writer?.apply {
            write(SampleCsv.format(s))
            newLine()
        }
    }

    fun stop() {
        writer?.close()
        writer = null
    }

    companion object {
        /** A CSV pushed to this path replaces the built-in scenario in replay mode. */
        fun customReplayFile(context: Context): File? =
            File(context.filesDir, "replay.csv").takeIf { it.exists() }
    }
}
