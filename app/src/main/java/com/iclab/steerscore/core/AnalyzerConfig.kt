package com.iclab.steerscore.core

/**
 * Penalties for the steering rules SteerScore adds on top of the speed-only baseline. Abrupt lane change and hard
 * cornering follow the KOTSA weights ([RiskWeights], about 4.1 and 3.8 points). The turn-signal and lane-departure
 * points are design values: the study has no weight for them, so they are to be set by a separate evaluation.
 */
data class Penalties(
    val abruptLaneChange: Float = RiskWeights.points(RiskWeights.ABRUPT_LANE_CHANGE),
    val unsignaledLaneChange: Float = 3f,
    val hardCornering: Float = RiskWeights.points(RiskWeights.HARD_TURN),
    val unsignaledTurn: Float = 3f,
    /** Drifting over the lane line and back. A design value. */
    val laneDeparture: Float = 2f,
)

/**
 * Steering detection thresholds. They work on estimated lateral acceleration rather than raw steering angle,
 * so the same steering input counts as more aggressive at higher speed.
 */
data class AnalyzerConfig(
    // Vehicle geometry for the kinematic bicycle model (roughly an IONIQ 5)
    val steeringRatio: Float = 14.3f,
    val wheelbaseM: Float = 3.0f,
    /**
     * Understeer term K (s²/m): yaw rate r = v·tan(δ)/(L + K·v²), so the car turns less than the kinematic model
     * predicts at speed. 0 is the plain kinematic model (the default, which the AI was trained with). Calibrated per
     * car together with [steeringRatio] from its gyro: real drives fit K ≈ 0.003–0.004 (comma2k19, A2D2).
     */
    val understeerK: Float = 0f,
    /** EMA factor applied to the steering angle at 10 Hz to suppress sensor jitter. */
    val steeringSmoothing: Float = 0.35f,

    // Lane change: one-sided lateral acceleration, then the opposite side, then back to straight
    val minLateralSpeedKmh: Float = 30f,
    val lateralEnter: Float = 0.6f,
    val lateralExit: Float = 0.25f,
    /** Straight this long ends a lane change. */
    val settleMs: Long = 500,
    /**
     * Before the counter-steer, a quiet spell must last this long to call it a one-sided correction. A slow
     * lane change can sit near zero for over a second between its halves (found by the synthetic evaluation).
     */
    val firstHalfSettleMs: Long = 1500,
    /** A single steering phase longer than this is a curve, not a lane change. */
    val maxPhaseMs: Long = 4000,
    val maxLaneChangeMs: Long = 8000,
    val abruptLateral: Float = 2.5f,
    val quickLaneChangeMs: Long = 2000,
    val quickLaneChangeLateral: Float = 1.5f,
    /** A turn signal seen this long before the maneuver started still counts as signaled. */
    val signalLookbackMs: Long = 3000,
    val hardCornerLateral: Float = 4.0f,
    /** Roughly 1 g; the bicycle model has no grip limit, so large steering at speed would otherwise explode. */
    val maxLateral: Float = 9.8f,

    // Telling lane changes, curves and turns apart by how far the car turned and moved sideways
    /** Time constant for tracking a curve's own lateral acceleration, so lane changes on it stand out. */
    val curveBaselineMs: Long = 1000,
    /**
     * Straight this long ends a curve. Longer than [settleMs] because a gentle lane change toward the outside
     * of a bend can hover near zero for about a second on its way across.
     */
    val curveEndSettleMs: Long = 1500,
    /** A lane change ends roughly parallel to where it started. */
    val maxLaneChangeHeadingDeg: Float = 20f,
    /** Less sideways movement than this is a wobble inside the lane, not a lane change (lanes are ~3.5 m). */
    val minLaneChangeOffsetM: Float = 1.5f,
    /**
     * Going out and straight back: further than this sideways counts as a lane change and a lane change back,
     * less as drifting over the lane line and back ([LaneDeparture]).
     */
    val laneReturnOffsetM: Float = 2.0f,
    /**
     * Going out and back by less than this stays inside the lane (a weave), more touches or crosses the line.
     * A 3.5 m lane leaves a 1.9 m car about 0.8 m on each side; the extra margin absorbs estimation error.
     */
    val minDepartureOffsetM: Float = 0.7f,
    /**
     * On a straight road the counter-steer half may last this long. Going out and straight back merges two
     * halves into one long lobe, which would otherwise read as the start of a curve.
     */
    val maxReturnPhaseMs: Long = 6000,

    // Intersection turns, at any speed the lane change detector ignores
    val minTurnSpeedKmh: Float = 3f,
    val turnEnterYawDegS: Float = 6f,
    val turnExitYawDegS: Float = 3f,
    val turnSettleMs: Long = 1000,
    val turnMinHeadingDeg: Float = 60f,
    val uTurnHeadingDeg: Float = 150f,
    /** Faster than this on average, a large heading change is a ramp or bend, not an intersection turn. */
    val turnMaxSpeedKmh: Float = 40f,
    val turnMaxDurationMs: Long = 30_000,
    /** Drivers signal a turn earlier than a lane change, while approaching the intersection. */
    val turnSignalLookbackMs: Long = 5000,

    val penalties: Penalties = Penalties(),
)
