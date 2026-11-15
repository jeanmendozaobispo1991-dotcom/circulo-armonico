package com.harmonylab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

data class NoteEvent(var pitch: Int, var start: Float, var length: Float, var velocity: Int = 100)

class ComposerView(context: Context) : View(context) {
    private val notes = mutableListOf<NoteEvent>()
    private val history = mutableListOf<List<NoteEvent>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var expanded = false
    private var selectedKey = 0
    private var gesture = ""
    private var downX = 0f
    private var downY = 0f
    private var activeNote = -1
    private var originalStart = 0f
    private var originalPitch = 60
    private var originalLength = 1f
    private var holdStart = 0L
    private var resizing = false
    private var draggingChord = false
    private var chordToPlace = intArrayOf()
    private val names = arrayOf("C","G","D","A","E","B","F♯","D♭","A♭","E♭","B♭","F")
    private val chords = mapOf(
        0 to listOf("C", "Dm", "Em", "F", "G", "Am"),
        7 to listOf("G", "Am", "Bm", "C", "D", "Em"),
        2 to listOf("D", "Em", "F♯m", "G", "A", "Bm"),
        9 to listOf("A", "Bm", "C♯m", "D", "E", "F♯m")
    )
    private val chordIntervals = mapOf(
        "C" to intArrayOf(0,4,7), "Dm" to intArrayOf(2,5,9), "Em" to intArrayOf(4,7,11),
        "F" to intArrayOf(5,9,12), "G" to intArrayOf(7,11,14), "Am" to intArrayOf(9,12,16),
        "Bm" to intArrayOf(11,14,16), "D" to intArrayOf(2,6,9), "A" to intArrayOf(9,13,16),
        "F♯m" to intArrayOf(6,9,13), "C♯m" to intArrayOf(1,4,8)
    )

    init { isClickable = true }

    fun setExpanded(value: Boolean) { expanded = value; invalidate() }
    fun notesSnapshot(): List<NoteEvent> = notes.map { it.copy() }
    fun undo() {
        if (history.isNotEmpty()) {
            notes.clear()
            notes.addAll(history.removeAt(history.lastIndex).map { it.copy() })
            invalidate()
        }
    }
    private fun saveHistory() { history.add(notes.map { it.copy() }) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(android.graphics.Color.rgb(17,20,29))
        val circleHeight = if (expanded) height * 0.17f else height * 0.34f
        drawCircleOfFifths(canvas, circleHeight)
        drawPianoRoll(canvas, circleHeight)
    }

    private fun drawCircleOfFifths(c: Canvas, h: Float) {
        val cx = width / 2f
        val cy = h / 2f + 8f
        val radius = min(width * 0.34f, h * 0.43f)
        paint.color = android.graphics.Color.rgb(31,37,51)
        paint.style = Paint.Style.FILL
        c.drawCircle(cx, cy, radius + 28f, paint)
        for (i in 0 until 12) {
            val a = Math.toRadians((-90.0 + i * 30.0))
            val x = cx + cos(a).toFloat() * radius
            val y = cy + sin(a).toFloat() * radius
            paint.color = if (i == selectedKey) android.graphics.Color.rgb(80,140,255) else android.graphics.Color.rgb(54,65,86)
            c.drawCircle(x, y, 22f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 12f
            paint.textAlign = Paint.Align.CENTER
            c.drawText(names[i], x, y + 4f, paint)
        }
        paint.color = android.graphics.Color.LTGRAY
        paint.textSize = 11f
        c.drawText("CÍRCULO DE QUINTAS", cx, 16f, paint)
        val list = chords[selectedKey]?.joinToString("   ") ?: "C   Dm   Em   F   G   Am"
        paint.textSize = 12f
        c.drawText(list, cx, h - 7f, paint)
    }

    private fun drawPianoRoll(c: Canvas, top: Float) {
        val left = 48f
        val right = width - 8f
        val bottom = height - 8f
        val rows = 24
        val rowH = (bottom - top) / rows
        val beatW = (right - left) / 8f
        paint.style = Paint.Style.FILL
        for (r in 0 until rows) {
            val midi = 83 - r
            paint.color = if (midi % 12 in listOf(1,3,6,8,10)) android.graphics.Color.rgb(25,29,40) else android.graphics.Color.rgb(34,40,54)
            c.drawRect(left, top + r*rowH, right, top + (r+1)*rowH, paint)
        }
        paint.color = android.graphics.Color.rgb(65,75,95)
        paint.strokeWidth = 1f
        for (b in 0..8) c.drawLine(left + b*beatW, top, left + b*beatW, bottom, paint)
        for (r in 0..rows) c.drawLine(left, top + r*rowH, right, top + r*rowH, paint)
        paint.color = android.graphics.Color.LTGRAY
        paint.textSize = 10f
        paint.textAlign = Paint.Align.RIGHT
        for (r in 0 until rows step 4) c.drawText((83-r).toString(), left-7f, top+r*rowH+rowH, paint)

        notes.forEachIndexed { index, n ->
            val x = left + n.start * beatW
            val y = top + (83 - n.pitch) * rowH
            val rect = RectF(x+1, y+1, x+n.length*beatW-1, y+rowH-1)
            paint.color = android.graphics.Color.rgb(71,145,255)
            c.drawRoundRect(rect, 5f, 5f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 9f
            c.drawText(" ", rect.left+4, rect.centerY()+3, paint)
            if (index == activeNote && resizing) {
                paint.color = android.graphics.Color.WHITE
                paint.textSize = 18f
                paint.textAlign = Paint.Align.CENTER
                c.drawText("↔", rect.right-2, rect.centerY()+6, paint)
            }
        }
        paint.textAlign = Paint.Align.LEFT
        paint.color = android.graphics.Color.LTGRAY
        paint.textSize = 10f
        c.drawText("PIANO ROLL  •  1/16", left, top-5f, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val top = if (expanded) height * 0.17f else height * 0.34f
        val left = 48f
        val beatW = (width - left - 8f) / 8f
        val rowH = (height - top - 8f) / 24f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; holdStart = System.currentTimeMillis()
                if (e.y < top) {
                    val dx = e.x - width/2f
                    val dy = e.y - top/2f - 8f
                    val radius = hypot(dx,dy)
                    if (radius in (min(width*0.34f, top*0.43f)-25f)..(min(width*0.34f, top*0.43f)+25f)) {
                        val angle = (Math.toDegrees(atan2(dy,dx).toDouble()) + 450.0) % 360.0
                        selectedKey = (angle / 30.0).roundToInt() % 12
                        chordToPlace = chordIntervals[(chords[selectedKey]?.firstOrNull() ?: "C")] ?: intArrayOf(0,4,7)
                        draggingChord = true
                        invalidate()
                        return true
                    }
                }
                activeNote = findNote(e.x,e.y,top,beatW,rowH)
                if (activeNote >= 0) {
                    val n = notes[activeNote]
                    originalStart = n.start; originalPitch = n.pitch; originalLength = n.length
                    gesture = "note"
                } else if (e.y >= top && e.x >= left) {
                    gesture = "create"
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingChord && e.y >= top) {
                    saveHistory()
                    val start = ((e.x-left)/beatW).coerceIn(0f,7f)
                    chordToPlace.forEach { interval -> notes.add(NoteEvent(60+interval, start, 1f)) }
                    draggingChord = false
                    invalidate()
                    return true
                }
                if (gesture == "note" && activeNote >= 0) {
                    val n = notes[activeNote]
                    if (!resizing && System.currentTimeMillis()-holdStart > 420 && abs(e.x-downX) < 18f && abs(e.y-downY) < 18f) resizing = true
                    if (resizing) {
                        n.length = (originalLength + (e.x-downX)/beatW).coerceIn(0.25f,8f)
                    } else {
                        n.start = (originalStart + (e.x-downX)/beatW).coerceIn(0f,7.75f)
                        n.pitch = (originalPitch - ((e.y-downY)/rowH).roundToInt()).coerceIn(48,83)
                    }
                    invalidate()
                    return true
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gesture == "create" && !draggingChord && e.y >= top && e.x >= left) {
                    saveHistory()
                    val start = ((min(downX,e.x)-left)/beatW).coerceIn(0f,7f)
                    val end = ((max(downX,e.x)-left)/beatW).coerceIn(start+0.25f,8f)
                    val pitch = (83 - ((e.y-top)/rowH).toInt()).coerceIn(48,83)
                    notes.add(NoteEvent(pitch,start,max(0.25f,end-start)))
                    PianoSynth.play(pitch)
                } else if (gesture == "note" && activeNote >= 0 && !resizing) {
                    val n = notes[activeNote]
                    PianoSynth.play(n.pitch)
                }
                gesture = ""; activeNote = -1; resizing = false; draggingChord = false
                invalidate()
                return true
            }
        }
        return true
    }

    private fun findNote(x: Float, y: Float, top: Float, beatW: Float, rowH: Float): Int {
        val idx = notes.indexOfLast { n ->
            val nx = 48f + n.start*beatW
            val ny = top + (83-n.pitch)*rowH
            x in nx..(nx+n.length*beatW) && y in ny..(ny+rowH)
        }
        return idx
    }
}
