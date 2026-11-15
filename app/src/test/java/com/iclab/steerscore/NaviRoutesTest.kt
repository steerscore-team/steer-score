package com.iclab.steerscore

import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.TBTInfo
import com.iclab.steerscore.core.Situation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NaviRoutesTest {

    /** The turn-by-turn list the emulator's navigation sent for 시흥동 → 중앙대학교광명병원 (logged 2026-11-14). */
    private val logged = listOf(
        "TURN_LEFT" to 80, "TURN_RIGHT" to 380, "TURN_AVOID_UNDER_R" to 730, "TURN_LEFT" to 430, "TURN_LEFT" to 680,
        "TURN_ENTER_IC_R" to 1400, "TURN_TOLL" to 13000, "TURN_EXIT_IC_R" to 6300, "TURN_LEFT" to 1900,
        "TURN_LEFT" to 680, "TURN_LEFT" to 2000, "TURN_LEFT" to 70, "TURN_ARRIVE" to 60,
    ).map { (t, d) -> TBTInfo(TurnType.valueOf(t), d.toDouble(), "") }

    @Test
    fun routeFromTheNavigationsTurnList() {
        // Distances are per step (they add up to the route length), so the expressway is the steps after the IC entry
        // up to the IC exit: 13.0 + 6.3 km
        val p = NaviRoutes.profileOf(RouteOption.RECOMMENDED, logged, 27_000.0, 2160, 1330)
        assertEquals(19.3, p.expresswayKm, 0.01)
        assertEquals(27.0, p.distanceKm, 0.01)
        assertEquals(36, p.timeMin)
        assertEquals(1330, p.tollWon)
        assertEquals(1, p.counts[Situation.IC_ENTRY])
        assertEquals(1, p.counts[Situation.MERGE])   // the IC entry merges onto the expressway
        assertEquals(1, p.counts[Situation.IC_EXIT])
        assertEquals(1, p.counts[Situation.TOLL])
        assertEquals(8, p.counts[Situation.INTERSECTION])
    }

    @Test
    fun guidedRouteStepsNameTheirPlaces() {
        // Part of the list getTBTInfo() returned on 2026-11-14 (판교 → 중앙대학교광명병원), with the navigation's descriptions
        val list = listOf(
            TBTInfo(TurnType.TURN_LEFT, 80.0, "기업지원허브 방면"),
            TBTInfo(TurnType.valueOf("TURN_ENTER_IC_C"), 80.0, "북청계IC 방면"),
            TBTInfo(TurnType.TURN_TOLL, 380.0, "북청계톨게이트"),
            TBTInfo(TurnType.valueOf("TURN_EXIT_IC_R"), 2800.0, "광명역IC - 광명역(KTX) 방면"),
            TBTInfo(TurnType.TURN_ARRIVE, 60.0, "도착"),
        )
        val steps = NaviRoutes.stepsOf(list)
        assertEquals(listOf(80.0, 160.0, 540.0, 3340.0, 3400.0), steps.map { it.atM })
        assertEquals(listOf("좌회전", "IC 진입", "톨게이트", "IC 진출", "도착"), steps.map { it.turn })
        assertEquals(listOf("기업지원허브", "북청계IC", "북청계톨게이트", "광명역IC", "도착"), steps.map { it.place })
        assertEquals(Situation.IC_ENTRY, steps[1].situation)
    }

    @Test
    fun withoutATurnListOnlyTheLengthIsKnown() {
        val p = NaviRoutes.profileOf(RouteOption.FREE, null, 12_000.0, 900, 0)
        assertFalse(p.composition)
        assertEquals(15, p.timeMin)
    }
}
