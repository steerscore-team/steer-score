package com.iclab.steerscore.core

/**
 * Where the driver goes: destinations of route guidance with how often and when, and which ones are pinned (★).
 * Seeded from the navigation's own recent destinations, counted once per guided drive. Listed pinned first, then by how
 * often, then by how recently, so the usual trips are at the top of the route recommendation.
 */
data class Place(
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String = "",
    val poiId: String = "",
    val poiSubId: String = "",
    val poiProvider: String = "",
    val count: Int = 0,
    val lastAt: Long = 0,
    val pinned: Boolean = false,
) {
    val point: GeoPoint get() = GeoPoint(lat, lon)

    /** The same destination: the same POI, or the same name within 300 m. */
    fun sameAs(o: Place): Boolean =
        (poiId.isNotBlank() && poiId == o.poiId) || (name == o.name && point.distanceM(o.point) < 300)
}

class DestinationHistory(places: List<Place> = emptyList()) {

    private val list = places.toMutableList()
    val places: List<Place> get() = list.toList()

    /** Pinned first, then most driven to, then most recent. */
    fun sorted(): List<Place> = list.sortedWith(
        compareByDescending<Place> { it.pinned }.thenByDescending { it.count }.thenByDescending { it.lastAt },
    )

    /**
     * A guided drive to [p] at [at] (ms). Counted once per drive: guidance to the same place within [SAME_DRIVE_MS] of
     * the last count (opening the screen again, re-routing, switching routes) is the same drive.
     */
    fun record(p: Place, at: Long): Boolean {
        val i = list.indexOfFirst { it.sameAs(p) }
        if (i < 0) {
            list += p.copy(count = 1, lastAt = at, pinned = false)
            return true
        }
        val old = list[i]
        if (old.count > 0 && at - old.lastAt < SAME_DRIVE_MS) return false
        list[i] = old.copy(count = old.count + 1, lastAt = at, poiId = old.poiId.ifBlank { p.poiId })
        return true
    }

    /** The navigation's recent destinations: added once (one drive each) when not known yet. */
    fun addRecent(recent: List<Place>, at: Long) {
        recent.forEachIndexed { k, p ->
            if (list.none { it.sameAs(p) }) list += p.copy(count = 1, lastAt = at - k, pinned = false)
        }
    }

    /** Example destinations with their counts (the demo's history), added when not known yet. */
    fun addSeed(seed: List<Place>) {
        seed.forEach { p -> if (list.none { it.sameAs(p) }) list += p }
    }

    fun togglePin(p: Place) {
        val i = list.indexOfFirst { it.sameAs(p) }
        if (i >= 0) list[i] = list[i].copy(pinned = !list[i].pinned)
    }

    companion object {
        const val SAME_DRIVE_MS = 30 * 60_000L
    }
}
