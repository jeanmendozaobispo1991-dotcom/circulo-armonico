package com.harmonylab

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var composer: ComposerView
    private lateinit var modeButton: Button
    private lateinit var playButton: Button
    private lateinit var bpmText: TextView
    private lateinit var gridButton: Button
    private lateinit var snapButton: Button

    private var expanded = false
    private var bpm = 120
    private var gridIdx = 2
    private var snapOn = true
    private val gridLabels = arrayOf("1/4", "1/8", "1/16", "1/32")
    private val gridSteps = floatArrayOf(1f, 0.5f, 0.25f, 0.125f)
    private val repeatHandler = Handler(Looper.getMainLooper())

    private val bg = Color.rgb(14, 17, 26)
    private val accent = Color.rgb(88, 150, 255)
    private val panel = Color.rgb(36, 43, 62)
    private val danger = Color.rgb(220, 80, 80)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun paintButton(b: Button, color: Int) {
        b.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(color)
        }
    }

    private fun styledButton(label: String, color: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(44)
            minimumHeight = dp(44)
            setPadding(dp(4), 0, dp(4), 0)
            paintButton(this, color)
            setOnClickListener { onClick() }
        }

    private fun changeBpm(delta: Int) {
        bpm = (bpm + delta).coerceIn(40, 240)
        bpmText.text = "$bpm BPM"
        composer.bpm = bpm
    }

    private fun repeatButton(label: String, delta: Int): Button {
        val b = styledButton(label, panel) {}
        b.textSize = 20f
        val repeat = object : Runnable {
            override fun run() {
                changeBpm(delta)
                repeatHandler.postDelayed(this, 70)
            }
        }
        b.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    changeBpm(delta)
                    repeatHandler.postDelayed(repeat, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    repeatHandler.removeCallbacks(repeat)
                }
            }
            true
        }
        return b
    }

    private fun weight(w: Float) = LinearLayout.LayoutParams(0, dp(44), w).apply {
        leftMargin = dp(3); rightMargin = dp(3)
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        page.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(
                insets.systemWindowInsetLeft + dp(10),
                insets.systemWindowInsetTop + dp(8),
                insets.systemWindowInsetRight + dp(10),
                insets.systemWindowInsetBottom + dp(8)
            )
            insets
        }

        composer = ComposerView(this)
        composer.bpm = bpm
        composer.gridStep = gridSteps[gridIdx]

        // Encabezado
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "HARMONY LAB"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            letterSpacing = 0.08f
        }
        modeButton = styledButton("Modo detallado", panel) {
            expanded = !expanded
            modeButton.text = if (expanded) "Modo composición" else "Modo detallado"
            composer.setExpanded(expanded)
        }
        modeButton.textSize = 12f
        modeButton.setPadding(dp(12), 0, dp(12), 0)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(modeButton, LinearLayout.LayoutParams(-2, dp(36)))
        page.addView(header)

        // Barra de transporte: Play, BPM, rejilla, imán
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playButton = styledButton("▶ Play", accent) {
            if (composer.isPlaying) composer.stop() else composer.play()
        }
        composer.onPlayState = { playing ->
            playButton.text = if (playing) "■ Stop" else "▶ Play"
            paintButton(playButton, if (playing) danger else accent)
        }
        bpmText = TextView(this).apply {
            text = "$bpm BPM"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        gridButton = styledButton("Rejilla " + gridLabels[gridIdx], panel) {
            gridIdx = (gridIdx + 1) % gridLabels.size
            gridButton.text = "Rejilla " + gridLabels[gridIdx]
            composer.gridStep = gridSteps[gridIdx]
        }
        gridButton.textSize = 12f
        snapButton = styledButton("Imán: SÍ", accent) {
            snapOn = !snapOn
            snapButton.text = if (snapOn) "Imán: SÍ" else "Imán: NO"
            paintButton(snapButton, if (snapOn) accent else panel)
            composer.snapOn = snapOn
        }
        snapButton.textSize = 12f

        transport.addView(playButton, weight(1.5f))
        transport.addView(repeatButton("−", -1), weight(0.7f))
        transport.addView(bpmText, weight(1.2f))
        transport.addView(repeatButton("+", 1), weight(0.7f))
        val lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(6) }
        page.addView(transport, lp)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(gridButton, weight(1f))
        row2.addView(snapButton, weight(1f))
        page.addView(row2, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        page.addView(composer, LinearLayout.LayoutParams(-1, 0, 1f))

        // Acciones inferiores
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val undo = styledButton("Deshacer", panel) { composer.undo() }
        val export = styledButton("Exportar MIDI", accent) {
            MidiExporter.share(this@MainActivity, composer.notesSnapshot())
        }
        actions.addView(undo, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            topMargin = dp(8); rightMargin = dp(5)
        })
        actions.addView(export, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            topMargin = dp(8); leftMargin = dp(5)
        })
        page.addView(actions)

        setContentView(page)
        page.requestApplyInsets()
    }

    override fun onPause() {
        composer.stop()
        super.onPause()
    }
}
