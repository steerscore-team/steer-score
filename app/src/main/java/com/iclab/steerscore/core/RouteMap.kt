package com.iclab.steerscore.core

/**
 * The map of one demo origin → destination pair for the route recommendation: OpenStreetMap roads and rivers around it
 * and the shape of each candidate route (index = order of the pair's routes in personas.txt). Built offline by
 * an offline script (route shapes from Valhalla, roads from the Overpass API; © OpenStreetMap contributors);
 * the app reads it from resources and never goes online.
 */
data class RouteMapData(
    val from: GeoPoint,
    val to: GeoPoint,
    val routes: Map<Int, List<GeoPoint>>,
    /** Road class (motorway, trunk, primary, secondary) and its line. */
    val roads: List<Pair<String, List<GeoPoint>>>,
    val rivers: List<List<GeoPoint>>,
)

object RouteMaps {
    /** The metro road background with each line's bounding box, for any live route. */
    private class Way(val cls: String?, val pts: List<GeoPoint>) {
        val minLat = pts.minOf { it.lat }
        val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }
        val maxLon = pts.maxOf { it.lon }
    }

    private val metro: List<Way> by lazy {
        val stream = RouteMaps::class.java.getResourceAsStream("/routes/metro.txt") ?: return@lazy emptyList()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.startsWith("B|") || it.startsWith("W|") }.mapNotNull { line ->
                val f = line.split('|')
                val pts = (if (f[0] == "B") f[3] else f[2]).trim().split(' ').filter { it.isNotEmpty() }
                    .map { p -> p.split(',').let { GeoPoint(it[0].toDouble(), it[1].toDouble()) } }
                if (pts.size < 2) null else Way(if (f[0] == "B") f[2] else null, pts)
            }.toList()
        }
    }

    /**
     * The real road map around [points] (metro background: expressways, trunk and primary roads, rivers), for drawing
     * under a path recorded from real location fixes. Empty outside the bundled area.
     */
    fun roadsAround(points: List<GeoPoint>): RouteMapData? {
        if (points.isEmpty()) return null
        val pad = 0.01
        val s = points.minOf { it.lat } - pad
        val n = points.maxOf { it.lat } + pad
        val w = points.minOf { it.lon } - pad * 1.25
        val e = points.maxOf { it.lon } + pad * 1.25
        val ways = metro.filter { it.maxLat >= s && it.minLat <= n && it.maxLon >= w && it.minLon <= e }
        if (ways.none { it.cls != null }) return null
        return RouteMapData(points.first(), points.last(), emptyMap(),
            ways.filter { it.cls != null }.map { it.cls!! to it.pts }, ways.filter { it.cls == null }.map { it.pts })
    }

    /**
     * A map for a live route: the metro roads and rivers around [from] (where the car is, if known) and [to], without
     * route lines (the navigation does not give the route's shape). Null outside the area of the bundled background.
     */
    fun around(from: GeoPoint?, to: GeoPoint): RouteMapData? {
        val ends = listOfNotNull(from, to)
        val span = if (from == null) 0.03 else maxOf(kotlin.math.abs(from.lat - to.lat), kotlin.math.abs(from.lon - to.lon) * 0.8)
        val pad = maxOf(0.02, span * 0.3)
        val s = ends.minOf { it.lat } - pad
        val n = ends.maxOf { it.lat } + pad
        val w = ends.minOf { it.lon } - pad * 1.25
        val e = ends.maxOf { it.lon } + pad * 1.25
        val ways = metro.filter { it.maxLat >= s && it.minLat <= n && it.maxLon >= w && it.minLon <= e }
        if (ways.none { it.cls != null }) return null
        // Long trips: leave out primary roads so the map stays readable
        val big = span > 0.25
        return RouteMapData(
            from = from ?: to,
            to = to,
            routes = emptyMap(),
            roads = ways.filter { it.cls != null && !(big && it.cls == "primary") }.map { it.cls!! to it.pts },
            rivers = ways.filter { it.cls == null }.map { it.pts },
        )
    }

    /**
     * The emulator's route guidance demo (bundled): the navigation's three routes from the emulator's
     * position to its demo destination, with what each goes through and their shapes.
     */
    class LiveDemo(
        val od: OdPair,
        val map: RouteMapData,
        val vias: Map<Int, Pair<GeoPoint, String>>,
        /** Place names in the navigation's turn descriptions that only that route passes. */
        val keywords: Map<Int, List<String>> = emptyMap(),
        /** The navigation's own turn texts of each route, captured on the emulator. */
        val turnTexts: Map<Int, List<String>> = emptyMap(),
    ) {
        /**
         * Which route the navigation guides along: the route whose captured turn texts overlap most with the ones the
         * navigation sends now (the driver may have picked it in the navigation's route screen), else by place names;
         * 0 (its own first route) otherwise.
         */
        /**
         * Where to send the navigation through so it takes route [index]: the point of that route's line farthest from
         * every other route (a spot only this route passes), away from both ends. Null without a line.
         */
        fun waypointFor(index: Int, attempt: Int = 0): GeoPoint? {
            val line = map.routes[index] ?: return null
            val others = map.routes.filterKeys { it != index }.values
            if (others.isEmpty() || line.size < 5) return null
            // Points of this route away from the others, farthest first; a later attempt takes the next one at least
            // 1 km from those already tried
            val ranked = line.subList(line.size * 15 / 100, line.size * 85 / 100)
                .map { p -> p to others.minOf { o -> o.minOf { it.distanceM(p) } } }
                .filter { it.second > 150 }
                .sortedByDescending { it.second }
                .map { it.first }
            val picked = mutableListOf<GeoPoint>()
            for (p in ranked) if (picked.all { it.distanceM(p) > 1_000 }) picked += p
            return picked.getOrNull(attempt)
        }

        fun guidedIndex(descriptions: List<String>): Int {
            val now = descriptions.toSet()
            turnTexts.entries.map { (i, t) -> i to t.toSet().let { s -> (s intersect now).size.toDouble() / (s union now).size.coerceAtLeast(1) } }
                .maxByOrNull { it.second }?.takeIf { it.second >= 0.5 }?.let { return it.first }
            return keywords.entries.firstOrNull { (_, kws) -> descriptions.any { d -> kws.any { it in d } } }?.key ?: 0
        }

    }

    /** Every guided-trip demo in the file (one per destination of the example history), by trip id. */
    val liveDemos: List<LiveDemo> by lazy {
        val lines = RouteMaps::class.java.getResourceAsStream("/routes/live_demo.txt")
            ?.bufferedReader(Charsets.UTF_8)?.readLines() ?: return@lazy emptyList()
        val ods = RouteDemo.parse(lines.filter { it.startsWith("OD|") || it.startsWith("ROUTE|") }).ods
        val maps = parse(lines)
        fun byTrip(prefix: String) = lines.filter { it.startsWith(prefix) }.map { it.split('|') }.groupBy { it[1] }
        val vias = byTrip("V|")
        val keywords = byTrip("K|")
        val turns = byTrip("T|")
        ods.mapNotNull { od ->
            val map = maps[od.id] ?: return@mapNotNull null
            LiveDemo(
                od, map,
                vias[od.id].orEmpty().associate { f -> f[2].toInt() to (f[3].split(',').let { GeoPoint(it[0].toDouble(), it[1].toDouble()) } to f[4]) },
                keywords[od.id].orEmpty().associate { f -> f[2].toInt() to f[3].split(',') },
                turns[od.id].orEmpty().associate { f -> f[2].toInt() to f[3].split(';') },
            )
        }
    }

    /** The navigation's routes for this destination ([to] within 1 km of a demo destination), if bundled. */
    fun liveDemoFor(to: GeoPoint): LiveDemo? = liveDemos.firstOrNull { it.map.to.distanceM(to) < 1_000 }

    private val all: Map<String, RouteMapData> by lazy {
        val stream = RouteMaps::class.java.getResourceAsStream("/routes/map.txt") ?: return@lazy emptyMap()
        parse(stream.bufferedReader(Charsets.UTF_8).readLines())
    }

    fun of(odId: String): RouteMapData? = all[odId]

    fun parse(lines: List<String>): Map<String, RouteMapData> {
        fun pt(s: String) = s.split(',').let { GeoPoint(it[0].toDouble(), it[1].toDouble()) }
        fun pts(s: String) = s.trim().split(' ').filter { it.isNotEmpty() }.map(::pt)
        val ends = mutableMapOf<String, Pair<GeoPoint, GeoPoint>>()
        val routes = mutableMapOf<String, MutableMap<Int, List<GeoPoint>>>()
        val roads = mutableMapOf<String, MutableList<Pair<String, List<GeoPoint>>>>()
        val rivers = mutableMapOf<String, MutableList<List<GeoPoint>>>()
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split('|')
            when (f[0]) {
                "P" -> ends[f[1]] = pt(f[2]) to pt(f[3])
                "R" -> routes.getOrPut(f[1]) { mutableMapOf() }[f[2].toInt()] = pts(f[3])
                "B" -> roads.getOrPut(f[1]) { mutableListOf() } += f[2] to pts(f[3])
                "W" -> rivers.getOrPut(f[1]) { mutableListOf() } += pts(f[2])
            }
        }
        return ends.mapValues { (od, e) ->
            RouteMapData(e.first, e.second, routes[od].orEmpty(), roads[od].orEmpty(), rivers[od].orEmpty())
        }
    }
}
