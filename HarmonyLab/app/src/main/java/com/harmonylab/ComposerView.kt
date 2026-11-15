package com.harmonylab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.*

data class NoteEvent(
    var pitch: Int,
    var start: Float,
    var length: Float,
    var velocity: Int = 100,
    var group: Int = 0,
    var locked: Boolean = false
)

class ComposerView(context: Context) : View(context) {

    companion object {
        const val MIN_P = 24
        const val MAX_P = 107
        const val TOTAL = 64f   // 16 compases de 4 pulsos

        private const val M_NONE = 0
        private const val M_CHORD = 1
        private const val M_NOTE = 2
        private const val M_RESIZE = 3
        private const val M_EMPTY = 4
        private const val M_LOCK = 5
        private const val M_PINCH = 6
    }

    private class Degree(val name: String, val notes: IntArray, val fill: Int, val whiteText: Boolean)
    private class GroupBox(
        val id: Int, var minStart: Float, var maxEnd: Float,
        var minPitch: Int, var maxPitch: Int, val locked: Boolean
    )

    // ---------- Estado ----------
    private val notes = mutableListOf<NoteEvent>()
    private val history = mutableListOf<List<NoteEvent>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val outerRect = RectF()
    private val innerRect = RectF()
    private val tmpRect = RectF()
    private val d = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val slop = 8f * d

    private var expanded = false
    private var selectedKey = 0

    // Gestos
    private var mode = M_NONE
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var histSaved = false
    private var downIdx = -1
    private var exitResize = false
    private var lockGroup = 0
    private var moving: List<NoteEvent> = emptyList()
    private var origS = FloatArray(0)
    private var origP = IntArray(0)
    private var lastDP = 0
    private var resizeIdx = -1
    private var resizeOrig = 1f
    private var resizeBaseX = 0f
    private var resizeGroup = 0
    private var rTargets: List<NoteEvent> = emptyList()
    private var rOrig = FloatArray(0)
    private var lastLen = 1f
    private var lastTapNote: NoteEvent? = null
    private var lastTapTime = 0L
    private var panB = 0f
    private var panP = 0f
    private var nextGroup = 1

    // Acorde que se arrastra
    private var chordToPlace = IntArray(0)
    private var chordName = ""
    private var dragX = 0f
    private var dragY = 0f

    // Vista (zoom y desplazamiento)
    private var ppb = 100f      // píxeles por pulso
    private var rowH = 60f      // alto de cada fila
    private var scrollB = 0f    // pulso en el borde izquierdo
    private var scrollP = 84f   // tono en el borde superior
    private var inited = false

    // Rejilla
    var gridStep = 0.25f
        set(v) { field = v; invalidate() }
    var snapOn = true
        set(v) { field = v; invalidate() }

    // Reproducción
    var bpm = 120
        set(v) { field = v; scheduleLive() }
    var loop = false
    var follow = false
    var onFollowChanged: ((Boolean) -> Unit)? = null
    private var builtSig = 0
    private var builtBpm = 0
    private var rebuilding = false
    private var rebuildAgain = false
    var onPlayState: ((Boolean) -> Unit)? = null
    var isPlaying = false
        private set
    private var playBeat = 0f
    private var lastTick = 0L

    // Geometría de la rueda
    private var wcx = 0f
    private var wcy = 0f
    private var wR = 0f

    private val keyNames = arrayOf("C","G","D","A","E","B","F♯","D♭","A♭","E♭","B♭","F")
    private val minorNames = arrayOf("Am","Em","Bm","F♯m","C♯m","G♯m","E♭m","B♭m","Fm","Cm","Gm","Dm")
    private val dimNames = arrayOf("B°","F♯°","C♯°","G♯°","D♯°","A♯°","E♯°","C°","G°","D°","A°","E°")
    private val noteNames = arrayOf("C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B")
    private val romans = arrayOf("I","ii","iii","IV","V","vi","vii°")

    // Colores
    private val cBg = Color.rgb(14, 17, 26)
    private val cPanel = Color.rgb(24, 29, 42)
    private val cAccent = Color.rgb(88, 150, 255)
    private val cMuted = Color.rgb(140, 150, 172)
    private val cRowWhite = Color.rgb(32, 38, 54)
    private val cRowBlack = Color.rgb(22, 26, 38)
    private val cLight = Color.rgb(238, 238, 242)
    private val cInk = Color.rgb(28, 30, 38)
    private val cBase1 = Color.rgb(252, 252, 253)
    private val cBase2 = Color.rgb(247, 247, 249)
    private val cBase3 = Color.rgb(243, 243, 246)
    private val cLine = Color.rgb(226, 226, 232)
    private val cOrange = Color.rgb(255, 140, 66)
    private val cOrangeL = Color.rgb(255, 188, 135)
    private val cPurple = Color.rgb(170, 100, 235)
    private val cPurpleL = Color.rgb(216, 176, 250)
    private val cYellow = Color.rgb(252, 236, 66)

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val sx = if (det.previousSpanX > 20f * d) det.currentSpanX / det.previousSpanX else det.scaleFactor
            val sy = if (det.previousSpanY > 20f * d) det.currentSpanY / det.previousSpanY else det.scaleFactor
            zoomAround(sx.coerceIn(0.5f, 2f), sy.coerceIn(0.5f, 2f), det.focusX, det.focusY)
            return true
        }
    })

    private val longPress = Runnable {
        if (mode == M_NOTE && !moved) {
            val n = notes.getOrNull(downIdx)
            if (n != null) {
                mode = M_RESIZE
                resizeIdx = downIdx
                resizeGroup = if (n.group > 0 && n.locked) n.group else 0
                beginResize(downX)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        }
    }

    private var sequence: PianoSynth.Sequence? = null
    private var preparing = false
    private var playToken = 0

    private val tick = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            val seq = sequence
            if (seq == null || seq.finished()) {
                stop()
                return
            }
            playBeat = seq.beat()
            if (follow) {
                val vb = viewBeats()
                if (playBeat > scrollB + vb * 0.92f || playBeat < scrollB) {
                    scrollB = playBeat - vb * 0.08f
                    clampScroll()
                }
            }
            invalidate()
            handler.postDelayed(this, 16)
        }
    }

    init { isClickable = true }

    // ---------- API pública ----------
    fun setExpanded(value: Boolean) { expanded = value; clampScroll(); invalidate() }
    fun notesSnapshot(): List<NoteEvent> = notes.map { it.copy() }

    fun undo() {
        if (history.isNotEmpty()) {
            notes.clear()
            notes.addAll(history.removeAt(history.lastIndex).map { it.copy() })
            clearResize()
            invalidate()
            scheduleLive()
        }
    }

    private fun notesSig(): Int {
        var h = 17
        for (n in notes) {
            h = h * 31 + n.pitch
            h = h * 31 + n.start.hashCode()
            h = h * 31 + n.length.hashCode()
        }
        return h * 31 + notes.size
    }

    private fun disableFollow() {
        if (follow && isPlaying) {
            follow = false
            onFollowChanged?.invoke(false)
        }
    }

    // ---------- Edición en vivo: el loop se renueva en la siguiente vuelta ----------
    private val liveRunnable = Runnable { doLive() }

    private fun scheduleLive() {
        if (!isPlaying) return
        handler.removeCallbacks(liveRunnable)
        handler.postDelayed(liveRunnable, 250)
    }

    private fun doLive() {
        val seq = sequence ?: return
        if (!isPlaying || !seq.isLoop) return
        val sig = notesSig()
        if (sig == builtSig && bpm == builtBpm) return
        if (rebuilding) {
            rebuildAgain = true
            return
        }
        rebuilding = true
        builtSig = sig
        builtBpm = bpm
        val snap = notes.map { it.copy() }
        val b = bpm
        Thread {
            val shorts = try { PianoSynth.renderSequence(snap, b, true) } catch (e: Exception) { null }
            handler.post {
                rebuilding = false
                if (sequence === seq && shorts != null) seq.queue(shorts, b)
                if (rebuildAgain) {
                    rebuildAgain = false
                    scheduleLive()
                }
            }
        }.start()
    }

    fun play() {
        if (isPlaying || preparing || notes.isEmpty()) return
        preparing = true
        val token = ++playToken
        val snapshot = notes.map { it.copy() }
        val b = bpm
        val lp = loop
        builtSig = notesSig()
        builtBpm = b
        onPlayState?.invoke(true)
        Thread {
            val shorts = try { PianoSynth.renderSequence(snapshot, b, lp) } catch (e: Exception) { null }
            handler.post {
                if (token != playToken) return@post
                preparing = false
                if (shorts == null) {
                    onPlayState?.invoke(false)
                    return@post
                }
                val seq = try { PianoSynth.createSequence(shorts, b, lp) } catch (e: Exception) { null }
                if (seq == null) {
                    onPlayState?.invoke(false)
                    return@post
                }
                sequence = seq
                isPlaying = true
                playBeat = 0f
                seq.start()
                handler.post(tick)
            }
        }.start()
    }

    fun stop() {
        if (!isPlaying && !preparing) return
        playToken++
        preparing = false
        isPlaying = false
        handler.removeCallbacks(tick)
        handler.removeCallbacks(liveRunnable)
        rebuildAgain = false
        sequence?.release()
        sequence = null
        onPlayState?.invoke(false)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    // ---------- Utilidades ----------
    private fun dp(v: Float) = v * d
    private fun saveHistory() {
        history.add(notes.map { it.copy() })
        if (history.size > 60) history.removeAt(0)
    }
    private fun ensureHistory() { if (!histSaved) { saveHistory(); histSaved = true } }
    private fun noteName(midi: Int) = noteNames[midi % 12] + (midi / 12 - 1)
    private fun isBlack(midi: Int) = midi % 12 in listOf(1, 3, 6, 8, 10)
    private fun clampF(v: Float, lo: Float, hi: Float) = max(lo, min(v, hi))

    private fun drawCentered(c: Canvas, text: String, x: Float, y: Float) {
        val fm = paint.fontMetrics
        c.drawText(text, x, y - (fm.ascent + fm.descent) / 2f, paint)
    }

    private fun snapBeat(b: Float): Float = if (snapOn) (b / gridStep).roundToInt() * gridStep else b
    private fun snapFloor(b: Float): Float = if (snapOn) floor(b / gridStep) * gridStep else b

    // ---------- Geometría ----------
    private fun split(): Float = if (expanded) dp(84f) else height * 0.46f
    private fun rulerTop() = split() + dp(8f)
    private fun gridTop() = rulerTop() + dp(20f)
    private fun gridLeft() = dp(44f)
    private fun gridRight() = width - dp(4f)
    private fun gridBottom() = height - dp(4f)
    private fun xOf(b: Float) = gridLeft() + (b - scrollB) * ppb
    private fun beatAt(x: Float) = scrollB + (x - gridLeft()) / ppb
    private fun yTop(p: Int) = gridTop() + (scrollP - (p + 1)) * rowH
    private fun pitchAt(y: Float) = floor(scrollP - (y - gridTop()) / rowH).toInt()
    private fun viewBeats() = (gridRight() - gridLeft()) / ppb
    private fun viewRows() = (gridBottom() - gridTop()) / rowH

    private fun clampScroll() {
        scrollB = clampF(scrollB, 0f, max(0f, TOTAL - viewBeats()))
        val hi = (MAX_P + 1).toFloat()
        val lo = MIN_P + viewRows()
        scrollP = if (lo >= hi) hi else clampF(scrollP, lo, hi)
    }

    private fun clampZoom() {
        val gw = gridRight() - gridLeft()
        val gh = gridBottom() - gridTop()
        ppb = clampF(ppb, max(gw / TOTAL, dp(10f)), dp(220f))
        rowH = clampF(rowH, max(dp(10f), gh / 84f), dp(48f))
    }

    private fun zoomAround(sx: Float, sy: Float, fx: Float, fy: Float) {
        val bAt = beatAt(fx)
        val pAt = scrollP - (fy - gridTop()) / rowH
        ppb *= sx
        rowH *= sy
        clampZoom()
        scrollB = bAt - (fx - gridLeft()) / ppb
        scrollP = pAt + (fy - gridTop()) / rowH
        clampScroll()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!inited && w > 0 && h > 0) {
            ppb = (gridRight() - gridLeft()) / 8f
            rowH = dp(22f)
            clampZoom()
            scrollB = 0f
            scrollP = 66f + viewRows() / 2f
            inited = true
        }
        clampScroll()
    }

    // ---------- Teoría ----------
    private fun pos(i: Int) = ((i % 12) + 12) % 12
    private fun majRoot(i: Int) = (pos(i) * 7) % 12
    private fun minRoot(i: Int) = (majRoot(i) + 9) % 12
    private fun dimRoot(i: Int) = (majRoot(i) + 11) % 12
    private fun chordNotes(root: Int, q: Int): IntArray {
        val third = if (q == 0) 4 else 3
        val fifth = if (q == 2) 6 else 7
        return intArrayOf(60 + root, 60 + root + third, 60 + root + fifth)
    }

    private fun degrees(): List<Degree> {
        val p = selectedKey
        val a = pos(p - 1)
        val b = pos(p + 1)
        return listOf(
            Degree(keyNames[p], chordNotes(majRoot(p), 0), cOrange, true),
            Degree(minorNames[a], chordNotes(minRoot(a), 1), cPurpleL, false),
            Degree(minorNames[b], chordNotes(minRoot(b), 1), cPurpleL, false),
            Degree(keyNames[a], chordNotes(majRoot(a), 0), cOrangeL, false),
            Degree(keyNames[b], chordNotes(majRoot(b), 0), cOrangeL, false),
            Degree(minorNames[p], chordNotes(minRoot(p), 1), cPurple, true),
            Degree(dimNames[p], chordNotes(dimRoot(p), 2), cYellow, false)
        )
    }

    // ---------- Grupos (bloques de acorde) ----------
    private fun groupBoxes(): List<GroupBox> {
        val map = LinkedHashMap<Int, GroupBox>()
        for (n in notes) {
            if (n.group <= 0) continue
            val g = map[n.group]
            if (g == null) {
                map[n.group] = GroupBox(n.group, n.start, n.start + n.length, n.pitch, n.pitch, n.locked)
            } else {
                g.minStart = min(g.minStart, n.start)
                g.maxEnd = max(g.maxEnd, n.start + n.length)
                g.minPitch = min(g.minPitch, n.pitch)
                g.maxPitch = max(g.maxPitch, n.pitch)
            }
        }
        return map.values.toList()
    }

    private fun lockPos(g: GroupBox): FloatArray {
        val r = dp(11f)
        val cx = max(xOf(g.minStart) + r, gridLeft() + r)
        val cy = max(yTop(g.maxPitch) - r - dp(1f), gridTop() + r)
        return floatArrayOf(cx, cy)
    }

    // ---------- Dibujo ----------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(cBg)
        drawCircleOfFifths(canvas, split())
        drawPianoRoll(canvas)
        drawDragGhost(canvas)
    }

    private fun computeWheel(h: Float) {
        val top = dp(24f)
        val bottom = h - dp(28f)
        wR = max(dp(20f), min((width - dp(24f)) / 2f, (bottom - top) / 2f))
        wcx = width / 2f
        wcy = (top + bottom) / 2f
    }

    private fun ringSegment(c: Canvas, rOut: Float, rIn: Float, center: Float, color: Int) {
        val start = center - 15f
        outerRect.set(wcx - rOut, wcy - rOut, wcx + rOut, wcy + rOut)
        innerRect.set(wcx - rIn, wcy - rIn, wcx + rIn, wcy + rIn)
        path.reset()
        path.arcTo(outerRect, start, 30f)
        path.arcTo(innerRect, start + 30f, -30f)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawPath(path, paint)
    }

    private fun ringText(c: Canvas, s: String, ang: Float, r: Float, size: Float) {
        val rad = Math.toRadians(ang.toDouble())
        val x = wcx + cos(rad).toFloat() * r
        val y = wcy + sin(rad).toFloat() * r
        var rot = ((ang + 90f) % 360f + 360f) % 360f
        if (rot > 90f && rot < 270f) rot += 180f
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        c.save()
        c.rotate(rot, x, y)
        drawCentered(c, s, x, y)
        c.restore()
    }

    private fun drawCircleOfFifths(c: Canvas, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = cLight
        c.drawRoundRect(RectF(dp(4f), dp(4f), width - dp(4f), h), dp(16f), dp(16f), paint)

        val deg = degrees()
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER

        if (expanded) {
            paint.color = Color.rgb(120, 126, 140)
            paint.textSize = dp(11f)
            drawCentered(c, "CÍRCULO DE QUINTAS  •  " + keyNames[selectedKey] + " mayor  •  arrastra un acorde", width / 2f, dp(15f))
            val cw = (width - dp(24f)) / 7f
            for (k in 0 until 7) {
                val l = dp(12f) + k * cw
                tmpRect.set(l + dp(2f), dp(28f), l + cw - dp(2f), h - dp(8f))
                paint.style = Paint.Style.FILL
                paint.color = deg[k].fill
                c.drawRoundRect(tmpRect, dp(10f), dp(10f), paint)
                paint.color = if (deg[k].whiteText) Color.WHITE else cInk
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textSize = dp(14f)
                drawCentered(c, deg[k].name, tmpRect.centerX(), tmpRect.centerY() - dp(6f))
                paint.typeface = Typeface.SERIF
                paint.textSize = dp(11f)
                drawCentered(c, romans[k], tmpRect.centerX(), tmpRect.centerY() + dp(12f))
            }
            paint.typeface = Typeface.DEFAULT
            return
        }

        paint.color = Color.rgb(120, 126, 140)
        paint.textSize = dp(11f)
        drawCentered(c, "CÍRCULO DE QUINTAS", width / 2f, dp(15f))

        computeWheel(h)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(222, 222, 228)
        c.drawCircle(wcx, wcy + dp(2f), wR + dp(3f), paint)

        for (i in 0 until 12) {
            val ang = -90f + ((i - selectedKey + 12) % 12) * 30f
            val sel = i == selectedKey
            val near = i == pos(selectedKey - 1) || i == pos(selectedKey + 1)
            ringSegment(c, wR, wR * 0.74f, ang, if (sel) cOrange else if (near) cOrangeL else cBase1)
            ringSegment(c, wR * 0.74f, wR * 0.52f, ang, if (sel) cPurple else if (near) cPurpleL else cBase2)
            ringSegment(c, wR * 0.52f, wR * 0.40f, ang, if (sel) cYellow else cBase3)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = cLine
        for (r in floatArrayOf(1f, 0.74f, 0.52f, 0.40f)) c.drawCircle(wcx, wcy, wR * r, paint)
        for (i in 0 until 12) {
            val rad = Math.toRadians(-90.0 + i * 30.0 + 15.0)
            c.drawLine(
                wcx + cos(rad).toFloat() * wR * 0.40f, wcy + sin(rad).toFloat() * wR * 0.40f,
                wcx + cos(rad).toFloat() * wR, wcy + sin(rad).toFloat() * wR, paint
            )
        }

        paint.style = Paint.Style.FILL
        paint.color = cInk
        for (i in 0 until 12) {
            val ang = -90f + ((i - selectedKey + 12) % 12) * 30f
            paint.typeface = Typeface.DEFAULT_BOLD
            ringText(c, keyNames[i], ang, wR * 0.87f, wR * 0.13f)
            paint.typeface = Typeface.DEFAULT
            ringText(c, minorNames[i], ang, wR * 0.63f, wR * 0.10f)
            ringText(c, dimNames[i], ang, wR * 0.46f, wR * 0.065f)
        }

        val chipR = wR * 0.085f
        for (k in 0 until 7) {
            val rad = Math.toRadians(-90.0 + k * 360.0 / 7.0)
            val x = wcx + cos(rad).toFloat() * wR * 0.26f
            val y = wcy + sin(rad).toFloat() * wR * 0.26f
            paint.style = Paint.Style.FILL
            paint.color = deg[k].fill
            c.drawCircle(x, y, chipR, paint)
            paint.color = if (deg[k].whiteText) Color.WHITE else cInk
            paint.typeface = Typeface.SERIF
            paint.textSize = chipR * 0.95f
            paint.textAlign = Paint.Align.CENTER
            drawCentered(c, romans[k], x, y)
        }
        paint.color = cInk
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = wR * 0.15f
        paint.textAlign = Paint.Align.CENTER
        drawCentered(c, keyNames[selectedKey], wcx, wcy)

        paint.color = Color.rgb(60, 100, 200)
        paint.textSize = dp(13f)
        drawCentered(c, deg.joinToString("    ") { it.name }, width / 2f, h - dp(14f))
        paint.typeface = Typeface.DEFAULT
    }

    private fun drawLock(c: Canvas, cx: Float, cy: Float, r: Float, locked: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (locked) cOrange else Color.rgb(90, 100, 125)
        c.drawCircle(cx, cy, r, paint)
        paint.color = Color.WHITE
        val bw = r * 0.95f
        val bh = r * 0.7f
        val bodyTop = cy - bh * 0.1f
        c.drawRoundRect(cx - bw / 2f, bodyTop, cx + bw / 2f, bodyTop + bh, dp(1.5f), dp(1.5f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.6f)
        val sx = if (locked) 0f else r * 0.3f
        val lift = if (locked) 0f else r * 0.12f
        val aTop = cy - r * 0.78f - lift
        val aBot = aTop + r * 0.6f
        tmpRect.set(cx - r * 0.3f + sx, aTop, cx + r * 0.3f + sx, aBot)
        c.drawArc(tmpRect, 180f, 180f, false, paint)
        val mid = (aTop + aBot) / 2f
        c.drawLine(cx + r * 0.3f + sx, mid, cx + r * 0.3f + sx, bodyTop, paint)
        if (locked) c.drawLine(cx - r * 0.3f, mid, cx - r * 0.3f, bodyTop, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawPianoRoll(c: Canvas) {
        val gl = gridLeft()
        val gr = gridRight()
        val gt = gridTop()
        val gb = gridBottom()
        val rt = rulerTop()

        paint.style = Paint.Style.FILL
        paint.color = cPanel
        c.drawRect(0f, rt, width.toFloat(), gt, paint)

        val pHi = min(MAX_P, floor(scrollP - 0.0001f).toInt())
        val pLo = max(MIN_P, floor(scrollP - (gb - gt) / rowH).toInt())

        // ----- Filas, rejilla y teclado -----
        c.save()
        c.clipRect(0f, gt, gr, gb)
        for (p in pHi downTo pLo) {
            val y = yTop(p)
            paint.style = Paint.Style.FILL
            paint.color = if (isBlack(p)) cRowBlack else cRowWhite
            c.drawRect(gl, y, gr, y + rowH, paint)
        }

        var ls = gridStep
        while (ls * ppb < dp(8f)) ls *= 2f
        val i0 = floor(scrollB / ls).toInt()
        val i1 = ceil((scrollB + viewBeats()) / ls).toInt()
        for (i in i0..i1) {
            val b = i * ls
            if (b < 0f || b > TOTAL) continue
            val x = xOf(b)
            val isBar = abs(b / 4f - round(b / 4f)) < 1e-4f
            val isBeat = abs(b - round(b)) < 1e-4f
            when {
                isBar -> { paint.color = Color.rgb(125, 140, 170); paint.strokeWidth = dp(1.5f) }
                isBeat -> { paint.color = Color.rgb(72, 84, 108); paint.strokeWidth = dp(1f) }
                else -> { paint.color = Color.rgb(44, 52, 70); paint.strokeWidth = 1f }
            }
            c.drawLine(x, gt, x, gb, paint)
        }

        paint.strokeWidth = 1f
        for (p in pHi downTo pLo) {
            val y = yTop(p)
            paint.color = Color.rgb(42, 50, 68)
            c.drawLine(gl, y, gr, y, paint)
            if (p % 12 == 0) {
                paint.color = Color.rgb(90, 104, 134)
                c.drawLine(gl, y + rowH, gr, y + rowH, paint)
            }
        }

        // Teclado lateral
        for (p in pHi downTo pLo) {
            val y = yTop(p)
            val black = isBlack(p)
            paint.style = Paint.Style.FILL
            paint.color = if (black) Color.rgb(30, 33, 42) else Color.rgb(228, 232, 240)
            c.drawRect(0f, y + 0.5f, gl - dp(2f), y + rowH - 0.5f, paint)
            if (rowH >= dp(15f) || p % 12 == 0) {
                paint.color = if (black) Color.rgb(180, 188, 205) else Color.rgb(40, 46, 62)
                paint.typeface = if (p % 12 == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                paint.textSize = min(dp(10f), rowH * 0.62f)
                paint.textAlign = Paint.Align.RIGHT
                drawCentered(c, noteName(p), gl - dp(6f), y + rowH / 2f)
            }
        }
        paint.typeface = Typeface.DEFAULT
        c.restore()

        // ----- Regla de compases -----
        c.save()
        c.clipRect(gl, rt, gr, gt)
        var barStep = 1
        while (barStep * 4f * ppb < dp(28f)) barStep *= 2
        val n0 = floor(scrollB / 4f / barStep).toInt()
        val n1 = ceil((scrollB + viewBeats()) / 4f / barStep).toInt()
        paint.color = cMuted
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = dp(10f)
        paint.textAlign = Paint.Align.LEFT
        paint.strokeWidth = dp(1f)
        for (n in n0..n1) {
            val bar = n * barStep
            val beat = bar * 4f
            if (beat < 0f || beat >= TOTAL) continue
            val x = xOf(beat)
            c.drawLine(x, gt - dp(8f), x, gt, paint)
            drawCentered(c, (bar + 1).toString(), x + dp(4f), rt + (gt - rt) / 2f - dp(2f))
        }
        paint.typeface = Typeface.DEFAULT
        c.restore()

        // ----- Notas, bloques, candados y cabezal -----
        c.save()
        c.clipRect(gl, gt, gr + dp(4f), gb)
        val rr = min(dp(4f), rowH / 3f)
        notes.forEachIndexed { index, n ->
            val x1 = xOf(n.start)
            val x2 = xOf(n.start + n.length)
            val y = yTop(n.pitch)
            if (x2 < gl || x1 > gr || y + rowH < gt || y > gb) return@forEachIndexed
            tmpRect.set(x1 + 1f, y + 1.5f, max(x2 - 1f, x1 + 3f), y + rowH - 1.5f)
            val lockedGroup = n.group > 0 && n.locked
            paint.style = Paint.Style.FILL
            paint.color = when {
                index == resizeIdx && resizeGroup == 0 -> Color.rgb(130, 185, 255)
                lockedGroup -> cOrange
                else -> cAccent
            }
            c.drawRoundRect(tmpRect, rr, rr, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f)
            paint.color = if (lockedGroup) cOrangeL else Color.rgb(190, 215, 255)
            c.drawRoundRect(tmpRect, rr, rr, paint)
            paint.style = Paint.Style.FILL
            if (tmpRect.width() > dp(34f) && rowH >= dp(13f)) {
                paint.color = Color.WHITE
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textSize = min(dp(11f), rowH * 0.62f)
                paint.textAlign = Paint.Align.LEFT
                drawCentered(c, noteName(n.pitch), tmpRect.left + dp(6f), tmpRect.centerY())
                paint.typeface = Typeface.DEFAULT
            }
        }

        val boxes = groupBoxes()
        for (g in boxes) {
            if (!g.locked) continue
            tmpRect.set(
                xOf(g.minStart) - dp(1f), yTop(g.maxPitch) - dp(1f),
                xOf(g.maxEnd) + dp(1f), yTop(g.minPitch) + rowH + dp(1f)
            )
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f)
            paint.color = cOrange
            c.drawRoundRect(tmpRect, dp(6f), dp(6f), paint)
        }
        for (g in boxes) {
            val p = lockPos(g)
            drawLock(c, p[0], p[1], dp(11f), g.locked)
        }

        // Ícono ↔ para estirar la nota
        val hi = handleInfo()
        if (hi != null) {
            val hx = hi[0]
            val hy = hi[1]
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            c.drawCircle(hx, hy, dp(13f), paint)
            paint.color = cInk
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = dp(17f)
            paint.textAlign = Paint.Align.CENTER
            drawCentered(c, "↔", hx, hy)
            paint.typeface = Typeface.DEFAULT
        }

        if (isPlaying) {
            val px = xOf(playBeat)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = Color.rgb(255, 90, 90)
            c.drawLine(px, rt, px, gb, paint)
        }
        c.restore()
        paint.style = Paint.Style.FILL
    }

    // Vista previa del acorde mientras se arrastra
    private fun drawDragGhost(c: Canvas) {
        if (mode != M_CHORD || chordToPlace.isEmpty()) return
        val pl = chordPlacement(dragX, dragY)
        if (pl != null) {
            c.save()
            c.clipRect(gridLeft(), gridTop(), gridRight(), gridBottom())
            for (m in chordToPlace) {
                val p = (m + pl.second).coerceIn(MIN_P, MAX_P)
                tmpRect.set(xOf(pl.first) + 1f, yTop(p) + 1.5f, xOf(pl.first + 1f) - 1f, yTop(p) + rowH - 1.5f)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(150, 255, 140, 66)
                c.drawRoundRect(tmpRect, dp(4f), dp(4f), paint)
            }
            c.restore()
        }
        paint.style = Paint.Style.FILL
        paint.color = cOrange
        c.drawCircle(dragX, dragY - dp(34f), dp(22f), paint)
        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = dp(14f)
        paint.textAlign = Paint.Align.CENTER
        drawCentered(c, chordName, dragX, dragY - dp(34f))
        paint.typeface = Typeface.DEFAULT
    }

    // ---------- Selección en la rueda ----------
    private fun pickChord(x: Float, y: Float): Boolean {
        computeWheel(split())
        val dx = x - wcx
        val dy = y - wcy
        val dist = hypot(dx, dy)
        if (dist > wR * 1.02f) return false
        if (dist < wR * 0.40f) {
            val deg = degrees()
            for (k in 0 until 7) {
                val a = Math.toRadians(-90.0 + k * 360.0 / 7.0)
                val px = wcx + cos(a).toFloat() * wR * 0.26f
                val py = wcy + sin(a).toFloat() * wR * 0.26f
                if (hypot(x - px, y - py) <= wR * 0.12f) {
                    chordToPlace = deg[k].notes
                    chordName = deg[k].name
                    return true
                }
            }
            return false
        }
        val rel = (Math.toDegrees(atan2(dy, dx).toDouble()) + 90.0 + 360.0) % 360.0
        val p = (selectedKey + ((rel + 15.0) / 30.0).toInt() % 12) % 12
        selectedKey = p
        when {
            dist > wR * 0.74f -> { chordToPlace = chordNotes(majRoot(p), 0); chordName = keyNames[p] }
            dist > wR * 0.52f -> { chordToPlace = chordNotes(minRoot(p), 1); chordName = minorNames[p] }
            else -> { chordToPlace = chordNotes(dimRoot(p), 2); chordName = dimNames[p] }
        }
        return true
    }

    private fun pickChip(x: Float, y: Float): Boolean {
        val h = split()
        if (y < dp(28f) || y > h - dp(8f) || x < dp(12f)) return false
        val cw = (width - dp(24f)) / 7f
        val k = ((x - dp(12f)) / cw).toInt()
        if (k !in 0..6) return false
        val dg = degrees()[k]
        chordToPlace = dg.notes
        chordName = dg.name
        return true
    }

    // ---------- Edición ----------
    private fun chordPlacement(x: Float, y: Float): Pair<Float, Int>? {
        if (chordToPlace.isEmpty()) return null
        if (x < gridLeft() || x > gridRight() || y < gridTop() || y > gridBottom()) return null
        val start = clampF(snapFloor(beatAt(x)), 0f, TOTAL - 1f)
        val off = (pitchAt(y) - chordToPlace[0]).coerceIn(MIN_P - chordToPlace.min(), MAX_P - chordToPlace.max())
        return Pair(start, off)
    }

    private fun dropChord(x: Float, y: Float) {
        if (chordToPlace.isEmpty()) return
        val pl = chordPlacement(x, y)
        if (pl != null) {
            saveHistory()
            val gid = nextGroup++
            for (m in chordToPlace) {
                notes.add(NoteEvent((m + pl.second).coerceIn(MIN_P, MAX_P), pl.first, 1f, 100, gid, true))
            }
        }
        val shift = pl?.second ?: 0
        PianoSynth.playChord(IntArray(chordToPlace.size) { chordToPlace[it] + shift })
        chordToPlace = IntArray(0)
    }

    private fun createNote(x: Float, y: Float) {
        if (x < gridLeft() || x > gridRight() || y < gridTop() || y > gridBottom()) return
        val start = snapFloor(beatAt(x))
        if (start < 0f || start >= TOTAL) return
        val len = min(lastLen, TOTAL - start)
        if (len < 0.06f) return
        val pitch = pitchAt(y).coerceIn(MIN_P, MAX_P)
        saveHistory()
        notes.add(NoteEvent(pitch, start, len))
        PianoSynth.play(pitch)
    }

    private fun tapNote() {
        val n = notes.getOrNull(downIdx) ?: return
        val now = SystemClock.uptimeMillis()
        if (n === lastTapNote && now - lastTapTime < 350) {
            saveHistory()
            if (n.group > 0 && n.locked) {
                val g = n.group
                notes.removeAll { it.group == g }
            } else {
                notes.removeAll { it === n }
            }
            lastTapNote = null
            clearResize()
        } else {
            lastTapNote = n
            lastTapTime = now
            PianoSynth.play(n.pitch)
        }
    }

    private fun toggleLock(id: Int) {
        val first = notes.firstOrNull { it.group == id } ?: return
        saveHistory()
        val newState = !first.locked
        for (n in notes) if (n.group == id) n.locked = newState
        clearResize()
    }

    private fun moveSelected(x: Float, y: Float) {
        if (moving.isEmpty()) return
        var dB = (x - downX) / ppb
        var dP = -((y - downY) / rowH).roundToInt()
        var minS = Float.MAX_VALUE
        var maxE = 0f
        var minP = Int.MAX_VALUE
        var maxP = Int.MIN_VALUE
        for (i in moving.indices) {
            minS = min(minS, origS[i])
            maxE = max(maxE, origS[i] + moving[i].length)
            minP = min(minP, origP[i])
            maxP = max(maxP, origP[i])
        }
        dB = snapBeat(minS + dB) - minS
        dB = clampF(dB, -minS, TOTAL - maxE)
        dP = max(MIN_P - minP, min(dP, MAX_P - maxP))
        for (i in moving.indices) {
            moving[i].start = origS[i] + dB
            moving[i].pitch = origP[i] + dP
        }
        if (dP != lastDP) {
            lastDP = dP
            if (moving.size == 1) PianoSynth.play(moving[0].pitch)
        }
    }

    private fun clearResize() { resizeIdx = -1; resizeGroup = 0 }

    private fun beginResize(baseX: Float) {
        rTargets = if (resizeGroup > 0) notes.filter { it.group == resizeGroup }
                   else listOfNotNull(notes.getOrNull(resizeIdx))
        rOrig = FloatArray(rTargets.size) { rTargets[it].length }
        resizeBaseX = baseX
    }

    // Posición del ícono ↔: [x, y, mitad del alto]. Nota suelta o bloque completo.
    private fun handleInfo(): FloatArray? {
        if (resizeGroup > 0) {
            val g = groupBoxes().firstOrNull { it.id == resizeGroup } ?: return null
            val top = yTop(g.maxPitch)
            val bottom = yTop(g.minPitch) + rowH
            return floatArrayOf(xOf(g.maxEnd), (top + bottom) / 2f, (bottom - top) / 2f)
        }
        val n = notes.getOrNull(resizeIdx) ?: return null
        return floatArrayOf(xOf(n.start + n.length), yTop(n.pitch) + rowH / 2f, rowH / 2f)
    }

    private fun resizeTo(x: Float) {
        if (rTargets.isEmpty()) return
        ensureHistory()
        val minLen = if (snapOn) gridStep else 0.0625f
        var maxEnd = 0f
        var minOrig = Float.MAX_VALUE
        for (i in rTargets.indices) {
            maxEnd = max(maxEnd, rTargets[i].start + rOrig[i])
            minOrig = min(minOrig, rOrig[i])
        }
        var dL = snapBeat(maxEnd + (x - resizeBaseX) / ppb) - maxEnd
        dL = clampF(dL, minLen - minOrig, TOTAL - maxEnd)
        for (i in rTargets.indices) rTargets[i].length = rOrig[i] + dL
        lastLen = rTargets[0].length
    }

    private fun findNote(x: Float, y: Float): Int {
        if (x < gridLeft() || y < gridTop() || y > gridBottom()) return -1
        for (i in notes.indices.reversed()) {
            val n = notes[i]
            val l = xOf(n.start)
            val r = xOf(n.start + n.length)
            val t = yTop(n.pitch)
            if (x >= l - dp(2f) && x <= r + dp(2f) && y >= t - dp(2f) && y <= t + rowH + dp(2f)) return i
        }
        return -1
    }

    private fun onHandle(x: Float, y: Float): Boolean {
        val h = handleInfo() ?: return false
        return abs(x - h[0]) <= dp(26f) && abs(y - h[1]) <= h[2] + dp(12f)
    }

    // ---------- Toques ----------
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        val a = e.actionMasked

        if (a == MotionEvent.ACTION_POINTER_DOWN) {
            handler.removeCallbacks(longPress)
            disableFollow()
            mode = M_PINCH
            return true
        }
        if (mode == M_PINCH) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) mode = M_NONE
            return true
        }

        when (a) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                moved = false; histSaved = false; downIdx = -1; exitResize = false
                handler.removeCallbacks(longPress)

                if (e.y < split()) {
                    val ok = if (expanded) pickChip(e.x, e.y) else pickChord(e.x, e.y)
                    if (ok) {
                        mode = M_CHORD
                        disableFollow()
                        dragX = e.x; dragY = e.y
                        invalidate()
                    } else mode = M_NONE
                    return true
                }

                disableFollow()
                for (g in groupBoxes()) {
                    val p = lockPos(g)
                    if (hypot(e.x - p[0], e.y - p[1]) <= dp(20f)) {
                        mode = M_LOCK
                        lockGroup = g.id
                        return true
                    }
                }

                if (resizeIdx >= 0) {
                    if (onHandle(e.x, e.y)) {
                        mode = M_RESIZE
                        beginResize(e.x)
                        return true
                    }
                    exitResize = true
                }

                val idx = findNote(e.x, e.y)
                if (idx >= 0) {
                    downIdx = idx
                    val n = notes[idx]
                    val lockedGroup = n.group > 0 && n.locked
                    moving = if (lockedGroup) notes.filter { it.group == n.group } else listOf(n)
                    origS = FloatArray(moving.size) { moving[it].start }
                    origP = IntArray(moving.size) { moving[it].pitch }
                    lastDP = 0
                    mode = M_NOTE
                    handler.postDelayed(longPress, 420)
                } else {
                    mode = M_EMPTY
                    panB = scrollB
                    panP = scrollP
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                    moved = true
                    handler.removeCallbacks(longPress)
                    if (exitResize) clearResize()
                }
                when (mode) {
                    M_CHORD -> { dragX = e.x; dragY = e.y; invalidate() }
                    M_NOTE -> if (moved) { ensureHistory(); moveSelected(e.x, e.y); invalidate() }
                    M_RESIZE -> { resizeTo(e.x); invalidate() }
                    M_EMPTY -> if (moved) {
                        scrollB = panB - (e.x - downX) / ppb
                        scrollP = panP + (e.y - downY) / rowH
                        clampScroll()
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (a == MotionEvent.ACTION_UP) {
                    if (exitResize && !moved && (mode == M_NOTE || mode == M_EMPTY)) {
                        clearResize()
                    } else {
                        when (mode) {
                            M_CHORD -> dropChord(e.x, e.y)
                            M_NOTE -> if (!moved) tapNote()
                            M_EMPTY -> if (!moved) createNote(downX, downY)
                            M_LOCK -> if (!moved) toggleLock(lockGroup)
                        }
                    }
                }
                mode = M_NONE
                invalidate()
                scheduleLive()
                return true
            }
        }
        return true
    }
}
