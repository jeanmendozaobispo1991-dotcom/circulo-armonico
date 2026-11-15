package com.harmonylab

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.exp

/**
 * Placeholder tone generator for interaction testing.
 * This is NOT a sampled acoustic piano. Replace with a licensed SoundFont engine
 * (e.g. FluidSynth) for production-quality piano sound.
 */
object PianoSynth {
    fun play(midi: Int) {
        thread(name = "HarmonyLabAudio") {
            val sampleRate = 44100
            val frequency = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val count = (sampleRate * 0.45).toInt()
            val samples = ShortArray(count)
            for (i in samples.indices) {
                val t = i.toDouble() / sampleRate
                val envelope = exp(-t * 5.5)
                val tone = sin(2.0 * PI * frequency * t) * 0.55 +
                    sin(2.0 * PI * frequency * 2.0 * t) * 0.22 +
                    sin(2.0 * PI * frequency * 3.0 * t) * 0.08
                samples[i] = (tone * envelope * Short.MAX_VALUE * 0.6).toInt().toShort()
            }
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                samples.size * 2, AudioTrack.MODE_STATIC
            )
            track.write(samples, 0, samples.size)
            track.play()
            Thread.sleep(500)
            track.release()
        }
    }

    private fun Double.pow(power: Double): Double = kotlin.math.exp(power * kotlin.math.ln(this))
}
