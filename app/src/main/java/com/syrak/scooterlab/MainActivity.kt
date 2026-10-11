
package com.syrak.scooterlab

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val bg = Color.rgb(10, 11, 16)
    private val panel = Color.rgb(30, 34, 46)
    private val red = Color.rgb(255, 55, 65)
    private val cyan = Color.rgb(50, 220, 240)
    private val green = Color.rgb(0, 230, 140)
    private lateinit var root: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = 0
        showDashboard()
    }

    private fun rounded(color: Int, stroke: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = 24f
            if (stroke != 0) setStroke(2, stroke)
        }
    }

    private fun text(
        value: String,
        size: Float,
        color: Int = Color.WHITE,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, 4, 0, 4)
        }
    }

    private fun section(title: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 18, 0, 8)
        }
        View(this).apply {
            setBackgroundColor(red)
            row.addView(this, LinearLayout.LayoutParams(4, 20))
        }
        row.addView(text("  ${title.uppercase()}", 12f, Color.LTGRAY, true))
        root.addView(row)
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            background = rounded(panel, Color.rgb(48, 53, 70))
            root.addView(
                this,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10 }
            )
        }
    }

    private fun line(label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            text(label, 13f, Color.LTGRAY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(text(value, 13f, Color.WHITE, true))
        root.addView(row)
    }

    private fun metricGrid() {
        val values = listOf(
            "⚡ VOLTAGE" to "40.6 V",
            "▥ CURRENT" to "10.2 A",
            "▰ BATTERY" to "87%",
            "♨ MOTOR TEMP" to "42 °C"
        )

        values.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            pair.forEach { (label, value) ->
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(14, 14, 8, 14)
                    background = rounded(panel)
                    addView(text(label, 10f, cyan, true))
                    addView(text(value, 21f, Color.WHITE, true))
                }
                row.addView(
                    tile,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { setMargins(0, 0, 8, 8) }
                )
            }
            root.addView(row)
        }
    }

    private fun actionButton(
        label: String,
        color: Int = red,
        action: () -> Unit
    ) {
        val button = Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = rounded(color)
            setOnClickListener { action() }
        }
        root.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 54
            ).apply {
                topMargin = 8
                bottomMargin = 8
            }
        )
    }

    private fun showDashboard() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 24)
        }

        scroll.addView(root)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = TextView(this).apply {
            text = "⚡"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(red)
            setPadding(10, 4, 10, 4)
        }
        header.addView(logo)
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 0, 0, 0)
            addView(text("SYRAK", 23f, Color.WHITE, true))
            addView(text("S C O O T E R L A B", 9f, Color.GRAY))
        }
        header.addView(
            brand,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(text("● DEMO", 11f, green, true))
        root.addView(header)

        status = text("●  DEMO MODE · NO CONTROLLER CONNECTED", 12f, cyan)
        status.setPadding(12, 12, 12, 12)
        status.background = rounded(Color.rgb(13, 37, 44))
        root.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 18; bottomMargin = 14 }
        )

        val connection = card()
        connection.addView(text("Scooter connection", 17f, Color.WHITE, true))
        connection.addView(text("Bluetooth controller not connected", 12f, Color.LTGRAY))
        actionButton("SCAN FOR SCOOTERS", green) {
            status.text = "● DEMO MODE · BLUETOOTH SCAN NOT IMPLEMENTED"
        }

        section("Live dashboard")
        val speed = card()
        speed.gravity = Gravity.CENTER
        speed.addView(text("SPEED", 11f, Color.GRAY, true))
        speed.addView(text("--", 66f, red, true))
        speed.addView(text("KM/H", 13f, Color.LTGRAY))
        speed.addView(text("NO LIVE DATA", 10f, green, true))

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf("POWER\n-- W", "DISTANCE\n-- KM", "RANGE\n-- KM").forEach {
            val item = text(it, 12f, Color.WHITE, true)
            item.gravity = Gravity.CENTER
            stats.addView(
                item,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        speed.addView(stats)

        section("Power")
        metricGrid()

        section("Thermal & cells")
        val battery = card()
        battery.addView(text("PACK HEALTH  ·  DEMO", 15f, green, true))
        battery.addView(text("Cell min                         -- V", 13f, Color.LTGRAY))
        battery.addView(text("Cell max                        -- V", 13f, Color.LTGRAY))
        battery.addView(text("Cell delta                      -- mV", 13f, Color.LTGRAY))
        battery.addView(text("Battery temperature          -- °C", 13f, Color.LTGRAY))

        section("Safety")
        val safety = card()
        safety.addView(text("Emergency stop", 17f, Color.WHITE, true))
        safety.addView(
            text("This demo cannot stop or control a scooter.", 13f, Color.LTGRAY)
        )
        actionButton("SAFETY INFORMATION") {
            status.text = "DEMO ONLY · USE THE PHYSICAL BRAKE"
        }

        section("Tuning")
        val tuning = card()
        tuning.addView(text("Speed settings preview", 17f, Color.WHITE, true))
        tuning.addView(
            text("No firmware changes are sent to the scooter.", 13f, Color.LTGRAY)
        )
        actionButton("TUNING UNAVAILABLE IN DEMO") {
            status.text = "TUNING DISABLED · NO CONTROLLER CONNECTION"
        }

        section("Console")
        val console = card()
        console.addView(text("> app: ready", 12f, cyan))
        console.addView(text("> connection: none", 12f, cyan))
        console.addView(text("> mode: simulated dashboard", 12f, cyan))
        console.addView(text("> firmware commands: disabled", 12f, cyan))
    }
}
