package com.iclab.steerscore

import android.os.Handler
import android.os.SystemClock
import com.iclab.steerscore.core.DrivingSample

/** Plays recorded or scripted samples back in real time (or faster) on the given handler's thread. */
class ReplayPlayer(
    private val samples: List<DrivingSample>,
    private val handler: Handler,
    private val onSample: (DrivingSample) -> Unit,
    private val onFinished: () -> Unit,
) {
    var speedFactor = 1f

    private var index = 0
    private var virtualMs = 0L
    private val baseMs = samples.firstOrNull()?.timeMs ?: 0L

    val isPlaying: Boolean get() = running
    private var running = false

    val progress: Float
        get() = if (samples.isEmpty()) 1f else index.toFloat() / samples.size

    private var lastTickMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            // Advance by the real time that passed, not the nominal tick, so a busy UI thread that runs
            // ticks late does not stretch the replay: a 170 s route at 4x takes 42.5 s
            val now = SystemClock.uptimeMillis()
            virtualMs += ((now - lastTickMs) * speedFactor).toLong()
            lastTickMs = now
            while (index < samples.size && samples[index].timeMs - baseMs <= virtualMs) {
                onSample(samples[index++])
            }
            if (index >= samples.size) {
                running = false
                onFinished()
            } else {
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        lastTickMs = SystemClock.uptimeMillis()
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    companion object {
        private const val TICK_MS = 50L
    }
}
