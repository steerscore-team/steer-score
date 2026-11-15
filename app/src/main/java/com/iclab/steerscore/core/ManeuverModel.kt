package com.iclab.steerscore.core

import kotlin.math.exp
import kotlin.math.max

/** What the car is doing at one moment, as the learned classifier sees it. Scores still come from the rules. */
enum class Maneuver(val label: String) {
    NONE("직진·보정"),
    LANE_CHANGE_LEFT("차선변경(좌)"),
    LANE_CHANGE_RIGHT("차선변경(우)"),
    TURN_LEFT("좌회전"),
    TURN_RIGHT("우회전"),
    U_TURN("유턴"),
    CURVE_LEFT("커브(좌)"),
    CURVE_RIGHT("커브(우)"),
    /** Over the lane line and straight back; added 2026-11-13 with [LaneDeparture]. */
    LANE_DEPARTURE("차선 이탈·복귀"),
    ;

    companion object {
        /** The class a rule-based steering event corresponds to, for comparing the two. */
        fun of(e: DrivingEvent): Maneuver? = when (e) {
            is LaneChange -> if (e.direction == Turn.LEFT) LANE_CHANGE_LEFT else LANE_CHANGE_RIGHT
            is IntersectionTurn -> when {
                e.uTurn -> U_TURN
                e.direction == Turn.LEFT -> TURN_LEFT
                else -> TURN_RIGHT
            }
            is Cornering -> if (e.direction == Turn.LEFT) CURVE_LEFT else CURVE_RIGHT
            is LaneDeparture -> LANE_DEPARTURE
            else -> null
        }
    }
}

/**
 * Input of the classifier for the sample at the center of a window: [WINDOW_HALF] samples (10 Hz) on each
 * side, every [STRIDE]th one, with four channels per point. The offline training script builds exactly the same vector.
 */
object ManeuverFeatures {
    const val WINDOW_HALF = 40
    const val STRIDE = 2
    const val POINTS = 2 * WINDOW_HALF / STRIDE + 1
    const val CHANNELS = 4
    const val SIZE = POINTS * CHANNELS
    const val DT_S = 0.1

    private const val YAW_SCALE = 30f
    private const val LATERAL_SCALE = 3f
    private const val SPEED_SCALE = 100f
    private const val HEADING_SCALE = 90f

    /**
     * [headingSum] is the running sum of yaw rate × [DT_S] (degrees) up to and including each sample, so the
     * heading change inside the window is a difference of two entries. Indices outside the trip are clamped.
     */
    fun window(center: Int, n: Int, yaw: FloatArray, lateral: FloatArray, speed: FloatArray, headingSum: DoubleArray): FloatArray {
        val out = FloatArray(SIZE)
        val first = (center - WINDOW_HALF).coerceIn(0, n - 1)
        var k = 0
        var offset = -WINDOW_HALF
        while (offset <= WINDOW_HALF) {
            val j = (center + offset).coerceIn(0, n - 1)
            out[k++] = yaw[j] / YAW_SCALE
            out[k++] = lateral[j] / LATERAL_SCALE
            out[k++] = speed[j] / SPEED_SCALE
            out[k++] = ((headingSum[j] - headingSum[first]) / HEADING_SCALE).toFloat()
            offset += STRIDE
        }
        return out
    }
}

/**
 * A small fully connected network (ReLU hidden layers, softmax output) trained offline.
 *
 * Text format, whitespace separated:
 * `steerscore-maneuver-mlp 1`, `classes <n> <names...>`, `scaler <size>` followed by the means and the
 * standard deviations, then for each layer `layer <in> <out>` followed by in×out weights (row = input)
 * and out biases.
 */
class ManeuverModel private constructor(
    val classes: List<Maneuver>,
    private val mean: FloatArray,
    private val std: FloatArray,
    private val layers: List<Layer>,
) {
    private class Layer(val inSize: Int, val outSize: Int, val weights: FloatArray, val bias: FloatArray)

    /** Class probabilities, in the order of [classes]. */
    fun predict(features: FloatArray): FloatArray {
        require(features.size == mean.size) { "expected ${mean.size} features, got ${features.size}" }
        var x = FloatArray(features.size) { (features[it] - mean[it]) / std[it] }
        layers.forEachIndexed { li, layer ->
            val y = layer.bias.copyOf()
            for (i in 0 until layer.inSize) {
                val xi = x[i]
                if (xi == 0f) continue
                val row = i * layer.outSize
                for (o in 0 until layer.outSize) y[o] += xi * layer.weights[row + o]
            }
            if (li < layers.lastIndex) for (o in y.indices) y[o] = max(0f, y[o])
            x = y
        }
        val top = x.max()
        var sum = 0f
        for (i in x.indices) {
            x[i] = exp(x[i] - top)
            sum += x[i]
        }
        for (i in x.indices) x[i] /= sum
        return x
    }

    companion object {
        fun parse(text: String): ManeuverModel {
            val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.iterator()
            fun next() = tokens.next()
            fun floats(n: Int) = FloatArray(n) { next().toFloat() }

            check(next() == "steerscore-maneuver-mlp" && next() == "1") { "not a maneuver model" }
            check(next() == "classes")
            val classes = List(next().toInt()) { Maneuver.valueOf(next()) }
            check(next() == "scaler")
            val size = next().toInt()
            check(size == ManeuverFeatures.SIZE) { "model expects $size features, app builds ${ManeuverFeatures.SIZE}" }
            val mean = floats(size)
            val std = floats(size).also { s -> for (i in s.indices) if (s[i] == 0f) s[i] = 1f }
            val layers = mutableListOf<Layer>()
            while (tokens.hasNext()) {
                check(next() == "layer")
                val inSize = next().toInt()
                val outSize = next().toInt()
                layers += Layer(inSize, outSize, floats(inSize * outSize), floats(outSize))
            }
            check(layers.last().outSize == classes.size)
            return ManeuverModel(classes, mean, std, layers)
        }
    }
}

/** The classifier's verdict on one rule-based event. */
data class AiVerdict(val maneuver: Maneuver, val confidence: Float) {
    /** True when the classifier agrees with what the rules called the event. */
    fun agreesWith(e: DrivingEvent): Boolean = Maneuver.of(e) == maneuver
}

/**
 * Per-sample features of a trip and, when a model is loaded, the classifier's label for every sample.
 * A sample is labeled once [ManeuverFeatures.WINDOW_HALF] later samples have arrived (4 s), or at [flush].
 */
class ManeuverTimeline(private val model: ManeuverModel?) {

    private var n = 0
    private var yaw = FloatArray(1024)
    private var lateral = FloatArray(1024)
    private var speed = FloatArray(1024)
    private var timeMs = LongArray(1024)
    private var headingSum = DoubleArray(1024)
    private var predicted = IntArray(1024)
    private var confidence = FloatArray(1024)
    private var labeled = 0

    val size: Int get() = n
    fun yawAt(i: Int) = yaw[i]
    fun lateralAt(i: Int) = lateral[i]
    fun speedAt(i: Int) = speed[i]
    fun labelAt(i: Int): Maneuver? = if (i < labeled && model != null) model.classes[predicted[i]] else null

    /** The classifier input for sample [i] given the samples seen so far. */
    fun featuresAt(i: Int): FloatArray = ManeuverFeatures.window(i, n, yaw, lateral, speed, headingSum)

    /** How many steering events the classifier has judged, and on how many it agrees with the rules. */
    fun agreement(events: List<DrivingEvent>, span: (DrivingEvent) -> LongRange?): Pair<Int, Int> {
        var judged = 0
        var agreed = 0
        for (e in events) {
            val s = span(e) ?: continue
            val v = verdict(s.first, s.last) ?: continue
            judged++
            if (v.agreesWith(e)) agreed++
        }
        return judged to agreed
    }

    fun add(t: Long, yawDegS: Float, lateralAcc: Float, speedKmh: Float) {
        if (n == yaw.size) grow()
        yaw[n] = yawDegS
        lateral[n] = lateralAcc
        speed[n] = speedKmh
        timeMs[n] = t
        headingSum[n] = (if (n == 0) 0.0 else headingSum[n - 1]) + yawDegS * ManeuverFeatures.DT_S
        n++
        while (labeled + ManeuverFeatures.WINDOW_HALF < n) label(labeled)
    }

    /** Labels the last samples too, with the window clamped at the end of the trip. */
    fun flush() {
        while (labeled < n) label(labeled)
    }

    private fun label(i: Int) {
        val m = model
        if (m == null) {
            labeled++
            return
        }
        val p = m.predict(ManeuverFeatures.window(i, n, yaw, lateral, speed, headingSum))
        var best = 0
        for (c in p.indices) if (p[c] > p[best]) best = c
        predicted[i] = best
        confidence[i] = p[best]
        labeled++
    }

    /**
     * The classifier's majority label between [startMs] and [endMs]: the most common maneuver if maneuvers
     * cover at least 40% of the span, otherwise [Maneuver.NONE]. Null when no model is loaded or the span
     * has not been labeled yet.
     */
    fun verdict(startMs: Long, endMs: Long): AiVerdict? {
        val m = model ?: return null
        val counts = IntArray(m.classes.size)
        val conf = FloatArray(m.classes.size)
        var total = 0
        for (i in 0 until labeled) {
            if (timeMs[i] < startMs || timeMs[i] > endMs) continue
            counts[predicted[i]]++
            conf[predicted[i]] += confidence[i]
            total++
        }
        if (total == 0 || (labeled < n && timeMs[labeled] <= endMs)) return null
        val none = m.classes.indexOf(Maneuver.NONE)
        var best = -1
        for (c in counts.indices) if (c != none && (best < 0 || counts[c] > counts[best])) best = c
        val maneuvers = total - (if (none >= 0) counts[none] else 0)
        val chosen = if (best >= 0 && counts[best] > 0 && maneuvers >= 0.4 * total) best else none
        return AiVerdict(m.classes[chosen], conf[chosen] / counts[chosen].coerceAtLeast(1))
    }

    private fun grow() {
        val size = yaw.size * 2
        yaw = yaw.copyOf(size)
        lateral = lateral.copyOf(size)
        speed = speed.copyOf(size)
        timeMs = timeMs.copyOf(size)
        headingSum = headingSum.copyOf(size)
        predicted = predicted.copyOf(size)
        confidence = confidence.copyOf(size)
    }
}
