package com.iclab.steerscore

import android.content.Context
import com.iclab.steerscore.core.DestinationHistory
import com.iclab.steerscore.core.Place
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** [DestinationHistory] in the app's private storage (destinations.json); it never leaves the car. */
class DestinationStore(context: Context) {

    private val file = File(context.filesDir, "destinations.json")
    private val seededFlag = File(context.filesDir, "destinations.seeded")

    /** The demo's example history was added once (the driver may remove or reorder it afterwards). */
    var seeded: Boolean
        get() = seededFlag.exists()
        set(v) { if (v) runCatching { seededFlag.writeText("1") } else seededFlag.delete() }

    fun load(): DestinationHistory = runCatching {
        val a = JSONArray(file.readText())
        DestinationHistory((0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Place(
                name = o.getString("name"), lat = o.getDouble("lat"), lon = o.getDouble("lon"),
                address = o.optString("address"), poiId = o.optString("poiId"), poiSubId = o.optString("poiSubId"),
                poiProvider = o.optString("poiProvider"), count = o.optInt("count"), lastAt = o.optLong("lastAt"),
                pinned = o.optBoolean("pinned"),
            )
        })
    }.getOrElse { DestinationHistory() }

    fun save(h: DestinationHistory) {
        val a = JSONArray()
        h.places.forEach { p ->
            a.put(JSONObject().apply {
                put("name", p.name); put("lat", p.lat); put("lon", p.lon); put("address", p.address)
                put("poiId", p.poiId); put("poiSubId", p.poiSubId); put("poiProvider", p.poiProvider)
                put("count", p.count); put("lastAt", p.lastAt); put("pinned", p.pinned)
            })
        }
        runCatching { file.writeText(a.toString()) }
    }
}
