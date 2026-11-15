package com.harmonylab

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.*

/**
 * Sintetizador de piano (síntesis aditiva). No es un piano grabado, pero tiene
 * ataque suave, armónicos, cuerdas ligeramente desafinadas y una caída natural
 * (los graves duran más que los agudos).
 *
 * Todas las notas que suenan juntas se mezclan en UN solo buffer de audio,
 * así arrancan exactamente al mismo tiempo.
 */
object PianoSynth {
    private const val SR = 44100
    private const val RELEASE = 0.3f   // segundos que tarda en apagarse una nota al terminar

    private val cache = ConcurrentHashMap<Int, FloatArray>()
    private val active = AtomicInteger(0)

    /** Secuencia ya renderizada, lista para reproducirse (con o sin loop). */
    class Sequence(
        private val track: AudioTrack,
        private val frames: Int,
        private val bpm: Int,
        private val loop: Boolean
    ) {
        fun start() { track.play() }

        /** Posición actual en pulsos, leída directamente del audio (sincronía exacta). */
        fun beat(): Float {
            val head = track.playbackHeadPosition
            val f = if (loop) head % frames else head
            return f.toFloat() / SR * bpm / 60f
        }

        fun finished(): Boolean = !loop && track.playbackHeadPosition >= frames

        fun release() {
            try { track.stop() } catch (e: Exception) { }
            try { track.release() } catch (e: Exception) { }
        }
    }

    /** Una sola nota (al tocar con el dedo). */
    fun play(midi: Int) = playChord(intArrayOf(midi))

    /** Varias notas exactamente al mismo tiempo (acordes). */
    fun playChord(midis: IntArray) {
        if (midis.isEmpty() || active.get() >= 12) return
        active.incrementAndGet()
        thread(name = "HarmonyLabAudio") {
            try {
                val bufs = midis.map { m -> cache.getOrPut(m) { renderNote(m, 1.3f, 0.7f) } }
                val len = bufs.maxOf { it.size }
                val mix = FloatArray(len)
                for (b in bufs) for (i in b.indices) mix[i] += b[i]
                val shorts = toShorts(mix)
                val track = makeTrack(shorts.size)
                track.write(shorts, 0, shorts.size)
                track.play()
                Thread.sleep(len * 1000L / SR + 150)
                track.release()
            } catch (e: Exception) {
            } finally {
                active.decrementAndGet()
            }
        }
    }

    /**
     * Renderiza todo el piano roll con la duración real de cada nota según el BPM.
     * Es pesado: llamar desde un hilo secundario.
     */
    fun buildSequence(notes: List<NoteEvent>, bpm: Int, loop: Boolean): Sequence? {
        if (notes.isEmpty()) return null
        val spb = 60.0 / bpm
        var endBeat = 0f
        for (n in notes) endBeat = max(endBeat, n.start + n.length)

        val frames: Int
        if (loop) {
            val loopBeats = ceil(endBeat / 4f) * 4f
            frames = (loopBeats * spb * SR).toInt()
        } else {
            frames = ((endBeat * spb + RELEASE + 0.1) * SR).toInt()
        }
        if (frames <= 0) return null

        val mix = FloatArray(frames)
        for (n in notes) {
            val buf = renderNote(n.pitch, (n.length * spb).toFloat(), RELEASE)
            val s0 = (n.start * spb * SR).toInt()
            for (i in buf.indices) {
                var idx = s0 + i
                if (idx >= frames) {
                    if (loop) idx %= frames else break
                }
                mix[idx] += buf[i]
            }
        }

        val shorts = toShorts(mix)
        val track = makeTrack(shorts.size)
        track.write(shorts, 0, shorts.size)
        if (loop) track.setLoopPoints(0, frames, -1)
        return Sequence(track, frames, bpm, loop)
    }

    // ---------- Síntesis ----------

    /** Una nota: se mantiene holdSec segundos y luego se apaga en relSec. */
    private fun renderNote(midi: Int, holdSec: Float, relSec: Float): FloatArray {
        val hold = max(holdSec, 0.05f)
        val total = ((hold + relSec) * SR).toInt()
        val out = FloatArray(total)

        // Forma de volumen: ataque suave y apagado final
        val shape = FloatArray(total)
        val holdN = min((hold * SR).toInt(), total)
        val att = max(1, (0.004 * SR).toInt())
        val relN = total - holdN
        for (i in 0 until total) {
            var g = 1f
            if (i < att) g = i.toFloat() / att
            if (i >= holdN && relN > 0) {
                val r = 1f - (i - holdN).toFloat() / relN
                g *= r * r
            }
            shape[i] = g
        }

        val f0 = 440.0 * 2.0.pow((midi - 69) / 12.0)
        // Los graves se sostienen más que los agudos
        val tau = (2.4 * 2.0.pow((60 - midi) / 30.0)).coerceIn(0.9, 6.0)
        val inharm = 0.0001 * exp((midi - 60) / 18.0)
        val amps = doubleArrayOf(1.0, 0.62, 0.38, 0.24, 0.14, 0.09, 0.06)
        val nyquist = SR * 0.45

        for (k in 1..7) {
            val fk = f0 * k * sqrt(1.0 + inharm * k * k)
            if (fk > nyquist) break
            val tauK = tau / (1.0 + 0.7 * (k - 1))
            val decay = exp(-1.0 / (tauK * SR))
            // Los dos primeros armónicos llevan dos "cuerdas" casi iguales: da cuerpo
            val mults = if (k <= 2) doubleArrayOf(1.0, 1.0007) else doubleArrayOf(1.0)
            val sAmp = if (k <= 2) doubleArrayOf(0.6, 0.5) else doubleArrayOf(1.0)
            for (s in mults.indices) {
                val w = 2.0 * PI * fk * mults[s] / SR
                val cw = cos(w)
                val sw = sin(w)
                var x = 0.0
                var y = 1.0
                var env = amps[k - 1] * sAmp[s] * 0.2
                for (i in 0 until total) {
                    out[i] += (x * env).toFloat() * shape[i]
                    val nx = x * cw + y * sw
                    val ny = y * cw - x * sw
                    x = nx
                    y = ny
                    env *= decay
                }
            }
        }
        return out
    }

    private fun toShorts(mix: FloatArray): ShortArray {
        var peak = 0f
        for (v in mix) peak = max(peak, abs(v))
        val scale = if (peak > 0.92f) 0.92f / peak else 1f
        return ShortArray(mix.size) {
            (mix[it] * scale * 32767f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun makeTrack(sampleCount: Int): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(sampleCount * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
}
