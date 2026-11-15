package com.harmonylab

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object MidiExporter {
    fun share(context: Context, notes: List<NoteEvent>) {
        val file = File(context.cacheDir, "harmony-lab.mid")
        FileOutputStream(file).use { it.write(buildMidi(notes)) }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".provider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/midi"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir archivo MIDI"))
    }

    private fun buildMidi(notes: List<NoteEvent>): ByteArray {
        val events = mutableListOf<Pair<Int, ByteArray>>()
        val ppq = 480
        for (n in notes) {
            val start = (n.start * ppq).toInt()
            val end = ((n.start + n.length) * ppq).toInt()
            events.add(start to byteArrayOf(0x90.toByte(), n.pitch.toByte(), n.velocity.toByte()))
            events.add(end to byteArrayOf(0x80.toByte(), n.pitch.toByte(), 0))
        }
        events.sortBy { it.first }
        val track = ArrayList<Byte>()
        var previous = 0
        for ((tick, data) in events) {
            writeVarLen(track, tick - previous)
            data.forEach { track.add(it) }
            previous = tick
        }
        writeVarLen(track, 0)
        track.add(0xFF.toByte()); track.add(0x2F.toByte()); track.add(0x00.toByte())
        val body = track.toByteArray()
        val out = ArrayList<Byte>()
        fun putInt(v: Int) { out.add((v shr 24).toByte()); out.add((v shr 16).toByte()); out.add((v shr 8).toByte()); out.add(v.toByte()) }
        fun putShort(v: Int) { out.add((v shr 8).toByte()); out.add(v.toByte()) }
        "MThd".toByteArray().forEach { out.add(it) }; putInt(6); putShort(0); putShort(1); putShort(ppq)
        "MTrk".toByteArray().forEach { out.add(it) }; putInt(body.size); body.forEach { out.add(it) }
        return out.toByteArray()
    }

    private fun writeVarLen(out: MutableList<Byte>, value: Int) {
        var buffer = value and 0x7F
        var v = value shr 7
        while (v > 0) { buffer = (buffer shl 8) or ((v and 0x7F) or 0x80); v = v shr 7 }
        while (true) {
            out.add(buffer.toByte())
            if (buffer and 0x80 != 0) buffer = buffer shr 8 else break
        }
    }
}
