package com.harmonylab

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var composer: ComposerView
    private lateinit var modeButton: Button
    private var expanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(17, 20, 29)
        window.navigationBarColor = android.graphics.Color.rgb(17, 20, 29)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.rgb(17, 20, 29))
            setPadding(16, 12, 16, 12)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "HARMONY LAB"
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            letterSpacing = 0.08f
        }
        modeButton = Button(this).apply {
            text = "Modo detallado"
            setOnClickListener {
                expanded = !expanded
                text = if (expanded) "Modo composición" else "Modo detallado"
                composer.setExpanded(expanded)
            }
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(modeButton)
        page.addView(header)

        val subtitle = TextView(this).apply {
            text = "Tonalidad: C mayor   •   Instrumento: Piano"
            textSize = 13f
            setTextColor(android.graphics.Color.LTGRAY)
            setPadding(0, 8, 0, 10)
        }
        page.addView(subtitle)

        composer = ComposerView(this)
        page.addView(composer, LinearLayout.LayoutParams(-1, 0, 1f))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val undo = Button(this).apply {
            text = "Deshacer"
            setOnClickListener { composer.undo() }
        }
        val export = Button(this).apply {
            text = "Exportar MIDI"
            setOnClickListener { MidiExporter.share(this@MainActivity, composer.notesSnapshot()) }
        }
        actions.addView(undo, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(export, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(actions)
        setContentView(page)
    }
}
