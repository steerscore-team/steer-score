package com.iclab.steerscore.core

/**
 * Speed-only rules of existing Korean driving scores, used as the baseline that SteerScore extends.
 *
 * None of these services publishes official thresholds or weights. Thresholds come from the sources noted
 * on each rule set. Hard acceleration and hard braking points follow the KOTSA weights ([RiskWeights]: 3 and about
 * 4.4), which also weigh hard braking most, as TMAP describes. Speeding keeps each rule set's design value until its
 * duration rule is validated. Both scores use the same per-event deductions so they stay comparable.
 */
data class SpeedRules(
    val id: String,
    val label: String,
    val source: String,
    /** Hard acceleration / braking: a speed change of at least the delta within this window. */
    val windowMs: Long,
    val accelerationDeltaKmh: Float,
    val brakingDeltaKmh: Float,
    val speedingMarginKmh: Float,
    val speedingMinMs: Long,
    val accelerationPenalty: Float,
    val brakingPenalty: Float,
    val speedingPenalty: Float,
    /** Applied at night (low beam on), since these services weigh night events more. */
    val nightMultiplier: Float,
    val cooldownMs: Long = 3000,
)

object SpeedRuleSets {

    val TMAP = SpeedRules(
        id = "tmap",
        label = "티맵 (1초 ±10km/h)",
        source = "티맵모빌리티 공식 안내(급감속·야간 가중) + 캐롯손해보험 안내 예시(1초당 ±10km/h, 제한속도 +15km/h)",
        windowMs = 1000,
        accelerationDeltaKmh = 10f,
        brakingDeltaKmh = 10f,
        speedingMarginKmh = 15f,
        speedingMinMs = 6000,
        accelerationPenalty = RiskWeights.points(RiskWeights.HARD_ACCELERATION),
        brakingPenalty = RiskWeights.points(RiskWeights.HARD_BRAKING),
        speedingPenalty = 3f,
        nightMultiplier = 1.5f,
    )

    val TMAP_3S = SpeedRules(
        id = "tmap3s",
        label = "티맵 (3초 ±30km/h)",
        source = "운전자 커뮤니티 해석(GPS 속도를 3초 평균해 ±30km/h, 제한속도 +15km/h)",
        windowMs = 3000,
        accelerationDeltaKmh = 30f,
        brakingDeltaKmh = 30f,
        speedingMarginKmh = 15f,
        speedingMinMs = 6000,
        accelerationPenalty = RiskWeights.points(RiskWeights.HARD_ACCELERATION),
        brakingPenalty = RiskWeights.points(RiskWeights.HARD_BRAKING),
        speedingPenalty = 3f,
        nightMultiplier = 1.5f,
    )

    val KAKAO = SpeedRules(
        id = "kakao",
        label = "카카오내비 (1초 ±10km/h)",
        source = "DB손해보험·언론 보도(과속·급가속·급감속·야간 합산, 급가속·급감속 비중 최대) + 운전자 커뮤니티(1초 10km/h)",
        windowMs = 1000,
        accelerationDeltaKmh = 10f,
        brakingDeltaKmh = 10f,
        speedingMarginKmh = 15f,
        speedingMinMs = 6000,
        accelerationPenalty = RiskWeights.points(RiskWeights.HARD_ACCELERATION),
        brakingPenalty = RiskWeights.points(RiskWeights.HARD_BRAKING),
        speedingPenalty = 2f,
        nightMultiplier = 1.5f,
    )

    val all = listOf(TMAP, TMAP_3S, KAKAO)

    fun byId(id: String?): SpeedRules = all.firstOrNull { it.id == id } ?: TMAP
}
