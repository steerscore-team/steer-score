package com.iclab.steerscore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachTest {

    private fun abruptUnsignaled(t: Long) = LaneChange(
        timeMs = t, direction = Turn.LEFT, durationMs = 2500, peakLateral = 3.5f, signaled = false, abrupt = true,
        basePenalty = 7f, multiplier = 1f, conditions = Conditions(),
    )

    private fun brake(t: Long) = HardBraking(t, 12f, 1000, 4.4f, 1f, Conditions(), SpeedRuleSets.TMAP.id)

    @Test
    fun saysOneShortLineRightAfterADeduction() {
        val c = Coach()
        c.onEvent(abruptUnsignaled(10_000), Situation.STRAIGHT)
        val line = c.poll(Coach.Context(timeMs = 13_000))
        assertEquals("차선 바꿀 땐 깜빡이 켜고 천천히요.", line?.text)
        // Normal events are never coached
        c.onEvent(abruptUnsignaled(50_000).copy(basePenalty = 0f), Situation.STRAIGHT)
        assertNull(c.poll(Coach.Context(timeMs = 60_000)))
    }

    @Test
    fun waitsWhileTheDriverIsBusyAndDropsStaleAdvice() {
        val c = Coach()
        c.onEvent(brake(100_000), Situation.IC_EXIT)
        // Still on the exit ramp, or the navigation is about to speak, or the car is turning: wait
        assertNull(c.poll(Coach.Context(timeMs = 101_000, situation = Situation.IC_EXIT)))
        assertNull(c.poll(Coach.Context(timeMs = 102_000, nextGuidanceM = 250.0)))
        assertNull(c.poll(Coach.Context(timeMs = 103_000, steeringDeg = 120f)))
        assertNull(c.poll(Coach.Context(timeMs = 104_000, speaking = true)))
        // Clear road a few seconds later: the line names the situation it was about
        assertEquals("나가는 길은 미리 감속해요.", c.poll(Coach.Context(timeMs = 106_000))?.text)

        // Held through a long merge, it is said right after the merge ends ...
        c.onEvent(abruptUnsignaled(200_000), Situation.STRAIGHT)
        assertNull(c.poll(Coach.Context(timeMs = 210_000, situation = Situation.MERGE)))
        assertNull(c.poll(Coach.Context(timeMs = 240_000, situation = Situation.MERGE)))
        assertEquals(Cause.UNSIGNALED_ABRUPT_LANE, c.poll(Coach.Context(timeMs = 241_000))?.cause)
        // ... but advice that could not be given within 35 s of a clear road is dropped
        c.onEvent(brake(300_000), Situation.STRAIGHT)
        assertNull(c.poll(Coach.Context(timeMs = 336_000)))
    }

    @Test
    fun linesChangeWithThePlaceAndWhatCameJustBefore() {
        val c = Coach()
        c.onEvent(abruptUnsignaled(0), Situation.STRAIGHT)
        assertEquals("차선 바꿀 땐 깜빡이 켜고 천천히요.", c.poll(Coach.Context(timeMs = 1_000))?.text)
        // The same habit later: another sentence, not the same words again
        c.onEvent(abruptUnsignaled(60_000), Situation.STRAIGHT)
        assertEquals("깜빡이 켜고 여유 있게 바꿔요.", c.poll(Coach.Context(timeMs = 61_000))?.text)
        // A cut-in (two lane changes 2 s apart): one short line, the third time says how often
        c.onEvent(abruptUnsignaled(120_000), Situation.STRAIGHT)
        c.onEvent(abruptUnsignaled(122_000), Situation.STRAIGHT)
        assertEquals("또 급하게 바꿨어요. 한 차선씩 천천히요.", c.poll(Coach.Context(timeMs = 123_000))?.text)
        // Two different deductions within a few seconds: one sentence naming both
        c.onEvent(brake(200_000), Situation.STRAIGHT)
        c.onEvent(LaneChange(timeMs = 203_000, direction = Turn.LEFT, durationMs = 2500, peakLateral = 3.5f, signaled = true,
            abrupt = true, basePenalty = 4f, multiplier = 1f, conditions = Conditions()), Situation.STRAIGHT)
        assertEquals("급감속에 급차선변경까지 있었어요. 조금 여유 있게 가요.", c.poll(Coach.Context(timeMs = 204_000))?.text)
        // The road decides the words: on the expressway, at an exit
        assertEquals("고속도로에선 깜빡이 켜고 천천히 들어가요.", Coach.phrase(Cause.UNSIGNALED_ABRUPT_LANE, Situation.STRAIGHT, RoadClass.EXPRESSWAY))
        assertEquals("고속도로에선 앞차와 거리를 더 둬요.", Coach.phrase(Cause.HARD_BRAKE, Situation.STRAIGHT, RoadClass.EXPRESSWAY))
        assertEquals("나가는 길은 미리 감속해요.", Coach.phrase(Cause.HARD_BRAKE, Situation.IC_EXIT, RoadClass.RAMP))
        assertEquals("교차로는 천천히 돌아요.", Coach.phrase(Cause.HARD_TURN, Situation.INTERSECTION))
        // The third time in a trip it counts
        val d = Coach()
        listOf(0L, 60_000L, 120_000L).forEach { d.onEvent(brake(it), Situation.STRAIGHT); d.poll(Coach.Context(timeMs = it + 1_000)) }
        assertEquals("급감속이 오늘 세 번째예요. 앞을 멀리 보고 미리 감속해요.", d.spoken.last().text)
    }

    @Test
    fun scenario2SaysEachDeductionAndTheCutInOnce() {
        // ② 속도 정상 · 조향 비정상: five steering deductions; the cut-in (two of them 2 s apart) is said once
        val sc = Scenarios.STEER_ONLY
        val a = TripAnalyzer(start = sc.start)
        val c = Coach()
        var deductions = 0
        var speakingUntil = 0L
        for (s in sc.build()) {
            a.onSample(s).filter { it.penalty > 0f && (it.isSteering || it.ruleId == SpeedRuleSets.TMAP.id) }
                .forEach { deductions++; c.onEvent(it, Situation.of(it, a.roadAt(it.timeMs))) }
            c.poll(Coach.Context(s.timeMs, s.road?.situation, null, s.steeringDeg, speaking = s.timeMs < speakingUntil))
                ?.let { speakingUntil = s.timeMs + 3_000; println("${s.timeMs / 1000}s \"${it.text}\" (event ${it.atMs / 1000}s)") }
        }
        assertEquals(5, deductions)
        assertEquals(4, c.spoken.size)
    }

    @Test
    fun realRoadDriveIsCoachedOutsideBusySections() {
        // Scenario ⑦ risky drive through the analyzer and the coach, as the app does it (without the TTS)
        val sc = Scenarios.routeTrip(1)
        val a = TripAnalyzer(start = sc.start)
        val c = Coach()
        val spokenAt = mutableListOf<Pair<Coach.Line, DrivingSample>>()
        var deductions = 0
        for (s in sc.build()) {
            a.onSample(s).filter { it.penalty > 0f && (it.isSteering || it.ruleId == SpeedRuleSets.TMAP.id) }
                .forEach { deductions++; c.onEvent(it, Situation.of(it, a.roadAt(it.timeMs))) }
            c.poll(Coach.Context(s.timeMs, s.road?.situation, null, s.steeringDeg))?.let { spokenAt += it to s }
        }
        spokenAt.forEach { (l, s) -> println("${s.timeMs / 1000}s ${s.road?.roadClass} ${l.cause} \"${l.text}\" (event ${l.atMs / 1000}s)") }
        // Every deduction is said (no two of them repeat a sentence back to back), never inside a merge / exit /
        // intersection section
        assertEquals(deductions, spokenAt.size)
        spokenAt.forEach { (_, s) -> assertTrue(s.road?.situation !in Coach.BUSY_SITUATIONS) }
        // The unsignaled abrupt lane change is coached, and so are the deductions made inside the busy merge and exit
        // sections, once those end
        val causes = spokenAt.map { it.first.cause }
        assertTrue(causes.toString(), Cause.UNSIGNALED_ABRUPT_LANE in causes)
        assertTrue(causes.toString(), Cause.HARD_ACCEL in causes && Cause.HARD_BRAKE in causes)
    }
}
