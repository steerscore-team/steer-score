package com.iclab.steerscore.core

/** Road class from the navigation (or, in scenario ⑦, from map data). Unknown without route guidance. */
enum class RoadClass(val label: String) {
    EXPRESSWAY("고속도로"),
    RAMP("진출입로"),
    GENERAL("일반도로"),
}

/**
 * What the navigation knows about the road at one moment: the road class and, near a guidance point, the situation
 * (IC entry, merge, IC exit, fork, roundabout, toll gate, intersection). Live it comes from NaviHelper turn-by-turn
 * guidance; the replay CSV can carry it too. Null fields mean the navigation does not say.
 */
data class RoadInfo(val roadClass: RoadClass?, val situation: Situation?)
