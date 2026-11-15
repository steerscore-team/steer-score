package com.iclab.steerscore.core

/**
 * CSV format for recorded drives, so a drive captured from the emulator or a real car can be replayed
 * through the same analyzer. An empty speed_limit_kmh means unknown.
 */
object SampleCsv {

    const val HEADER = "time_ms,speed_kmh,steering_deg,turn_signal,brake,wiper,low_beam,fog,speed_limit_kmh,lat,lon,road_class,situation"

    fun format(s: DrivingSample): String = listOf(
        s.timeMs,
        "%.2f".format(java.util.Locale.US, s.speedKmh),
        "%.2f".format(java.util.Locale.US, s.steeringDeg),
        s.turnSignal.name,
        s.brakePressed.toBit(),
        "0", // wiper: column kept for older files, no longer used
        s.conditions.lowBeam.toBit(),
        "0", // fog light: column kept for older files, no longer used
        s.speedLimitKmh?.toString() ?: "",
        s.location?.let { "%.6f".format(java.util.Locale.US, it.lat) } ?: "",
        s.location?.let { "%.6f".format(java.util.Locale.US, it.lon) } ?: "",
        s.road?.roadClass?.name ?: "",
        s.road?.situation?.name ?: "",
    ).joinToString(",")

    /** Parses rows after the header; malformed rows are skipped. */
    fun parse(lines: Sequence<String>): List<DrivingSample> = lines
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("time_ms") }
        .mapNotNull { line ->
            val c = line.split(",")
            if (c.size < 8) return@mapNotNull null
            runCatching {
                DrivingSample(
                    timeMs = c[0].toLong(),
                    speedKmh = c[1].toFloat(),
                    steeringDeg = c[2].toFloat(),
                    turnSignal = Turn.valueOf(c[3]),
                    brakePressed = c[4] == "1",
                    conditions = Conditions(lowBeam = c[6] == "1"),
                    speedLimitKmh = c.getOrNull(8)?.takeIf { it.isNotBlank() }?.toFloat(),
                    // Older recordings have no location columns
                    location = c.getOrNull(9)?.toDoubleOrNull()?.let { lat ->
                        c.getOrNull(10)?.toDoubleOrNull()?.let { lon -> GeoPoint(lat, lon) }
                    },
                    // Navigation road information (scenario ⑦, or a drive recorded with route guidance)
                    road = road(c.getOrNull(11), c.getOrNull(12)),
                )
            }.getOrNull()
        }
        .toList()

    private fun road(roadClass: String?, situation: String?): RoadInfo? {
        val rc = roadClass?.takeIf { it.isNotBlank() }?.let { n -> RoadClass.entries.firstOrNull { it.name == n } }
        val st = situation?.takeIf { it.isNotBlank() }?.let { n -> Situation.entries.firstOrNull { it.name == n } }
        return if (rc == null && st == null) null else RoadInfo(rc, st)
    }

    private fun Boolean.toBit() = if (this) "1" else "0"
}
