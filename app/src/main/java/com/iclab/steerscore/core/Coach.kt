package com.iclab.steerscore.core

/**
 * Real-time voice coaching, rule based (no LLM). Right after a deduction it picks one short, actionable sentence
 * so the driver can do it right at the next similar moment, and decides WHEN to say it so it does not add to the
 * driver's load or talk over the navigation:
 *
 * - Only deductions are coached, one sentence per message, phrased as what to do next ("깜빡이 먼저요"), never a
 *   score or a reproach while driving; the numbers wait for the after-trip report.
 * - Not while the driver is busy: inside a navigation situation (IC entry/exit, merge, fork, roundabout, toll,
 *   intersection), while the next navigation guidance point is close (the navigation is about to speak), or while
 *   the car is turning.
 * - Every deduction gets its own line. A line held back because the driver was busy is said as soon as the busy
 *   stretch ends (a workload manager: hold messages while the driving task is demanding, deliver them when it eases).
 *   Lines are said in the order the deductions happened, one after another, never over each other.
 * - What is said depends on the moment and on what came just before, so the same habit does not get the same line
 *   over and over: each habit has a few sentences for the road (expressway, ramp, general road) and the situation
 *   (merge, exit, intersection, curve), used in turn; a deduction within [repeatWindowMs] of another one is folded
 *   into it (two of the same: "또예요." once; two different: one sentence naming both); the third time in a trip it
 *   says how often it has happened.
 * - A line that could not be said within [maxAgeMs] of the event (or of the end of the busy stretch that held it), or
 *   at all within [maxHoldMs], is dropped: late advice is no longer about the moment it refers to.
 *
 * Times are the samples' own clock, so a replay at 4x behaves like the drive.
 */
class Coach(
    private val maxAgeMs: Long = 35_000,
    private val maxHoldMs: Long = 90_000,
    private val repeatWindowMs: Long = 10_000,
    /** The navigation speaks before its guidance points; stay quiet inside this distance. */
    private val quietBeforeGuidanceM: Double = 400.0,
) {
    data class Line(val text: String, val cause: Cause, val atMs: Long, val priority: Float)

    /** What the car is doing now, for deciding whether it is a good moment to talk. */
    data class Context(
        val timeMs: Long,
        val situation: Situation? = null,
        /** Distance to the next navigation guidance point; null without route guidance. */
        val nextGuidanceM: Double? = null,
        val steeringDeg: Float = 0f,
        /** The TTS is still speaking the previous line. */
        val speaking: Boolean = false,
    )

    /** Lines waiting to be said, oldest first. */
    private val queue = ArrayDeque<Line>()
    /** Last time the queue was held back because the driver was busy (or the previous line was still playing). */
    private var heldBusyMs: Long? = null
    private var lastText: String? = null
    private var lastSpokenMs = Long.MIN_VALUE / 2
    /** Cause and event time of the line said last, to notice the same habit again within [repeatWindowMs]. */
    private var lastSpokenCause: Cause? = null
    private var lastSpokenEventMs = Long.MIN_VALUE / 2
    private val counts = mutableMapOf<Cause, Int>()

    /** Lines said so far in this trip, for the report and tests. */
    val spoken = mutableListOf<Line>()

    /** A new event from the analyzer; [situation] is where it happened, [road] the road class when guided. */
    fun onEvent(e: DrivingEvent, situation: Situation, road: RoadClass? = null) {
        if (e.penalty <= 0f) return
        val cause = Cause.of(e) ?: return
        val n = (counts[cause] ?: 0) + 1
        counts[cause] = n
        val priority = e.penalty + if (e.isSteering) 10f else 0f
        // Within a few seconds of a line still waiting: one line for both
        val last = queue.lastOrNull()
        if (last != null && e.timeMs - last.atMs <= repeatWindowMs) {
            val text = if (last.cause == cause) again(cause, situation, road, n) else both(last.cause, cause)
            queue.removeLast()
            queue.addLast(Line(text, if (priority > last.priority) cause else last.cause, last.atMs, maxOf(priority, last.priority)))
            return
        }
        val text = when {
            // The same habit right after it was just said
            lastSpokenCause == cause && e.timeMs - lastSpokenEventMs <= repeatWindowMs -> again(cause, situation, road, n)
            n == 3 || n == 5 -> "${cause.label}${subject(cause.label)} 오늘 ${ORDINAL[n]}예요. " + phrase(cause, situation, road, n)
            else -> phrase(cause, situation, road, n)
        }
        if (queue.any { it.text == text }) return
        queue.addLast(Line(text, cause, e.timeMs, priority))
    }

    /** Returns the line to say now, or null. Call on every sample. */
    fun poll(c: Context): Line? {
        // Drop lines that are too old to still be about their moment
        while (true) {
            val head = queue.firstOrNull() ?: return null
            val since = maxOf(head.atMs, heldBusyMs ?: head.atMs)
            if (c.timeMs - since > maxAgeMs || c.timeMs - head.atMs > maxHoldMs) queue.removeFirst() else break
        }
        if (busy(c)) {
            heldBusyMs = c.timeMs
            return null
        }
        val line = queue.removeFirst()
        // The same sentence just said for the deduction a moment ago: merge, do not repeat it
        if (line.text == lastText && c.timeMs - lastSpokenMs < repeatWindowMs) return null
        lastText = line.text
        lastSpokenMs = c.timeMs
        lastSpokenCause = line.cause
        lastSpokenEventMs = line.atMs
        spoken += line
        return line
    }

    fun busy(c: Context): Boolean =
        c.speaking ||
            c.situation in BUSY_SITUATIONS ||
            (c.nextGuidanceM?.let { it < quietBeforeGuidanceM } == true) ||
            kotlin.math.abs(c.steeringDeg) > TURNING_DEG

    companion object {
        /** Situations with high workload or navigation prompts: merging, exits, junctions, intersections. */
        val BUSY_SITUATIONS = setOf(
            Situation.IC_ENTRY, Situation.MERGE, Situation.IC_EXIT, Situation.FORK,
            Situation.ROUNDABOUT, Situation.TOLL, Situation.INTERSECTION,
        )
        const val TURNING_DEG = 45f

        private val ORDINAL = mapOf(3 to "세 번째", 5 to "다섯 번째")

        /** Short labels for a sentence naming two deductions. */
        private val SHORT = mapOf(
            Cause.UNSIGNALED_ABRUPT_LANE to "깜빡이 없는 급차선변경", Cause.ABRUPT_LANE to "급차선변경",
            Cause.UNSIGNALED_LANE to "깜빡이 없는 차선변경", Cause.UNSIGNALED_TURN to "깜빡이 없는 회전",
            Cause.LANE_DEPARTURE to "차선 이탈", Cause.HARD_TURN to "급회전", Cause.HARD_BRAKE to "급감속",
            Cause.HARD_ACCEL to "급가속", Cause.SPEEDING to "과속",
        )

        /** 이 / 가 after [word], by whether its last syllable has a final consonant. */
        private fun subject(word: String): String {
            val c = word.lastOrNull() ?: return "이"
            return if (c in '가'..'힣' && (c - '가') % 28 == 0) "가" else "이"
        }

        private fun pick(list: List<String>, n: Int) = list[(n - 1).coerceAtLeast(0) % list.size]

        /** The same habit again within a few seconds (a cut-in): short, without repeating the whole advice. */
        fun again(cause: Cause, situation: Situation, road: RoadClass?, n: Int): String = when (cause) {
            Cause.UNSIGNALED_ABRUPT_LANE, Cause.ABRUPT_LANE, Cause.UNSIGNALED_LANE -> "또 급하게 바꿨어요. 한 차선씩 천천히요."
            Cause.HARD_BRAKE -> "또 급정거예요. 거리를 더 둬요."
            Cause.HARD_ACCEL -> "또 급가속이에요. 천천히 밟아요."
            Cause.SPEEDING -> "아직 빨라요. 속도를 줄여요."
            else -> "또예요. " + phrase(cause, situation, road, n)
        }

        /** Two different deductions within a few seconds: one sentence for both. */
        fun both(first: Cause, second: Cause): String =
            "${SHORT.getValue(first)}에 ${SHORT.getValue(second)}까지 있었어요. 조금 여유 있게 가요."

        /**
         * One short sentence (about 2–3 s) saying what to do next time, for where it happened: the road class from the
         * navigation (expressway, ramp, general road) and the situation (merge, exit, intersection, curve). Each place has
         * a few sentences used in turn ([n] = how many times this habit has come up in the trip), so a repeated habit
         * does not get the same words every time.
         */
        fun phrase(cause: Cause, situation: Situation, road: RoadClass? = null, n: Int = 1): String {
            val merge = situation == Situation.MERGE || situation == Situation.IC_ENTRY
            val express = road == RoadClass.EXPRESSWAY
            val ramp = road == RoadClass.RAMP || situation == Situation.IC_EXIT || situation == Situation.IC_ENTRY
            val options = when (cause) {
                Cause.UNSIGNALED_ABRUPT_LANE -> when {
                    merge -> listOf("합류할 땐 깜빡이 켜고 천천히요.", "합류 전에 깜빡이부터 켜요.")
                    express -> listOf("고속도로에선 깜빡이 켜고 천천히 들어가요.", "빠를수록 차선은 천천히요. 깜빡이 먼저요.")
                    else -> listOf("차선 바꿀 땐 깜빡이 켜고 천천히요.", "깜빡이 켜고 여유 있게 바꿔요.", "옆 차가 알 수 있게 깜빡이 먼저요.")
                }
                Cause.ABRUPT_LANE -> when {
                    express -> listOf("고속도로에선 차선을 더 천천히 바꿔요.", "속도가 높을수록 차선은 길게 바꿔요.")
                    else -> listOf("차선은 조금 더 천천히 바꿔요.", "핸들을 천천히 돌려 차선을 바꿔요.")
                }
                Cause.UNSIGNALED_LANE -> listOf("차선 바꿀 땐 깜빡이 먼저요.", "깜빡이로 먼저 알려 주세요.")
                Cause.UNSIGNALED_TURN -> when (situation) {
                    Situation.ROUNDABOUT -> listOf("회전교차로에서도 깜빡이 먼저요.")
                    else -> listOf("돌기 전에 깜빡이 먼저요.", "회전 전에 깜빡이를 켜요.")
                }
                Cause.LANE_DEPARTURE -> listOf("차선 가운데를 지켜요.", "차선 안쪽으로 붙여 달려요.")
                Cause.HARD_TURN -> when {
                    situation == Situation.INTERSECTION -> listOf("교차로는 천천히 돌아요.", "회전 전에 속도를 줄여요.")
                    ramp -> listOf("진출입로 커브는 천천히요.", "램프에선 미리 감속해요.")
                    else -> listOf("커브 전에 미리 속도를 줄여요.", "커브에선 미리 감속해요.")
                }
                Cause.HARD_BRAKE -> when {
                    situation == Situation.IC_EXIT -> listOf("나가는 길은 미리 감속해요.", "진출로 전에 속도를 줄여요.")
                    express -> listOf("고속도로에선 앞차와 거리를 더 둬요.", "멀리 보고 일찍 감속해요.")
                    else -> listOf("앞을 멀리 보고 미리 감속해요.", "신호를 미리 보고 천천히 서요.")
                }
                Cause.HARD_ACCEL -> when {
                    merge -> listOf("합류 가속은 부드럽게요.", "합류할 땐 천천히 속도를 올려요.")
                    else -> listOf("가속은 부드럽게요.", "출발은 천천히 밟아요.")
                }
                Cause.SPEEDING -> when {
                    express -> listOf("제한속도를 넘었어요. 속도를 줄여요.", "고속도로 제한속도를 넘었어요.")
                    else -> listOf("제한속도를 넘었어요.", "속도를 조금 줄여요.")
                }
            }
            return pick(options, n)
        }
    }
}
