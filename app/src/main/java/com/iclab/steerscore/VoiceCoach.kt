package com.iclab.steerscore

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import java.util.Locale

/**
 * Speaks the coach's lines with the Pleos TextToSpeech SDK in ON_DEVICE mode (no app registration or network).
 * The audio is tagged as navigation guidance so the car's audio system treats it like the navigation's own
 * prompts (same stream, ducks media) instead of as music. [speaking] lets the coach avoid talking over itself.
 */
class VoiceCoach(private val context: Context) {

    @Volatile var speaking = false
        private set
    @Volatile var ready = false
        private set
    private var tts: TextToSpeech? = null

    private val listener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() {
            speaking = true
            Log.i(TAG, "coach voice: start")
        }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() {
            speaking = false
            Log.i(TAG, "coach voice: done")
        }
        override fun onStop() { speaking = false }
        override fun onError(errorType: ErrorType) {
            speaking = false
            Log.w(TAG, "tts error $errorType")
        }
    }

    fun start() {
        if (tts != null) return
        runCatching {
            val t = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
            tts = t
            // initialize() first: adding the listener before it throws inside the SDK (checked on the emulator)
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching {
                        t.addEventListener(listener)
                        t.setLocale(Locale.KOREA)
                        t.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                        ready = true
                        Log.i(TAG, "coach voice ready")
                    }.onFailure { Log.w(TAG, "tts setup: ${it.message}") }
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "tts init failed $errorType")
                }
            })
        }.onFailure { Log.w(TAG, "tts unavailable: ${it.message}") }
    }

    fun say(text: String) {
        val t = tts ?: return
        if (!ready) return
        runCatching {
            speaking = true
            Log.i(TAG, "coach says: $text")
            t.speak(text, true)
        }.onFailure {
            speaking = false
            Log.w(TAG, "tts speak: ${it.message}")
        }
    }

    /**
     * The alert-tone mode: a short two-note chime instead of a sentence, on the navigation guidance audio (same volume
     * as the navigation, ducks media). The driver can then ask Gleo AI what it was about.
     */
    fun chime() {
        Log.i(TAG, "coach chime")
        Thread {
            runCatching {
                val rate = 22_050
                val notes = listOf(880.0 to 0.12, 660.0 to 0.16)
                val pcm = ShortArray(notes.sumOf { (rate * it.second).toInt() })
                var i = 0
                notes.forEach { (f, d) ->
                    val n = (rate * d).toInt()
                    for (k in 0 until n) {
                        val env = kotlin.math.min(1.0, kotlin.math.min(k / 300.0, (n - k) / 900.0))
                        pcm[i++] = (kotlin.math.sin(2 * Math.PI * f * k / rate) * 0.5 * env * Short.MAX_VALUE).toInt().toShort()
                    }
                }
                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder().setSampleRate(rate).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build(),
                    )
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                speaking = true
                track.play()
                Log.i(TAG, "coach chime")
                Thread.sleep(450)
                track.release()
            }.onFailure { Log.w(TAG, "chime: ${it.message}") }
            speaking = false
        }.start()
    }

    fun stop() {
        runCatching { tts?.stop() }
        speaking = false
    }

    fun release() {
        tts?.let { t ->
            runCatching { t.removeEventListener(listener) }
            runCatching { t.release() }
        }
        tts = null
        ready = false
        speaking = false
    }

    private companion object {
        const val TAG = "SteerScore"
    }
}
