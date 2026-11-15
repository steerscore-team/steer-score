package com.iclab.steerscore.core

/**
 * Relative weights of the risky-driving behaviours, from the Korea Transportation Safety Authority (교통안전공단)
 * study: "사업용차량 통합단말 표준플랫폼 및 안전운전지원기술 개발 최종보고서" (국토교통부·국토교통과학기술진흥원,
 * 2017-06), printed p.167, 표 68 "위험운전 요인별 가중치". The 2025 국토교통부 truck eco-driving campaign uses the same
 * weights, rounded.
 *
 * They are an initial reference, not a validated passenger-car scale: the study combined city-bus DTG analyses
 * (rankings turned into weights under two assumptions), an expert AHP survey (50 responses) and a 20-person bus/taxi
 * passenger test. SteerScore keeps the ratios and sets the scale with [BASE_POINTS]: one hard acceleration costs
 * 3 points, and the others cost 3 × (their weight ÷ the hard-acceleration weight).
 *
 * Behaviours the study has no weight for (turn signal, lane departure, speeding duration) keep design values.
 */
object RiskWeights {
    const val HARD_ACCELERATION = 0.1803f
    const val HARD_TURN = 0.2275f
    const val ABRUPT_LANE_CHANGE = 0.2453f
    const val HARD_BRAKING = 0.2642f
    const val SPEEDING = 0.0827f

    /** Points for one hard acceleration (B). A product setting, to be re-checked on real drives. */
    const val BASE_POINTS = 3f

    fun points(weight: Float): Float = BASE_POINTS * weight / HARD_ACCELERATION
}
