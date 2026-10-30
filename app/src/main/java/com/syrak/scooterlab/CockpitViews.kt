package com.syrak.scooterlab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Halbkreis-Tacho fuer das Cockpit.
 *
 * Zeichnet ausschliesslich Werte, die von aussen uebergeben werden.
 * Es gibt keine eingebauten Demo-Werte: Ist [available] false, wird
 * "Nicht verfuegbar" dargestellt.
 */
class CockpitGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var value: Float? = null
    private var maxValue: Float = 0f
    private var unit: String = ""
    private var label: String = ""
    private var available: Boolean = false
    private var demo: Boolean = false

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Palette.GRID_LINE
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Palette.ACCENT
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Palette.OUTLINE
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT_PRIMARY
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT_SECONDARY
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT_MUTED
        textAlign = Paint.Align.CENTER
    }

    private val demoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.WARNING
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val arcRect = RectF()

    fun setValue(value: Float?, max: Float, unit: String, label: String, demo: Boolean = false) {
        this.value = value
        this.maxValue = if (max > 0f) max else 1f
        this.unit = unit
        this.label = label
        this.available = value != null
        this.demo = demo
        invalidate()
    }

    fun setUnavailable(label: String) {
        this.value = null
        this.available = false
        this.demo = false
        this.unit = ""
        this.label = label
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val stroke = min(w, h) * 0.085f
        val margin = stroke / 2f + 4f

        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        arcPaint.color = when {
            demo -> Palette.WARNING
            !available -> Palette.TEXT_MUTED
            else -> Palette.ACCENT
        }

        // Halbkreis: 180 Grad Start bei 9 Uhr, Sweep 180 Grad.
        arcRect.set(margin, margin + h * 0.06f, w - margin, (w - margin) * 1.0f)
        canvas.drawArc(arcRect, 180f, 180f, false, trackPaint)

        val fraction = if (available && value != null) {
            (value!! / maxValue).coerceIn(0f, 1f)
        } else 0f

        if (fraction > 0f) {
            canvas.drawArc(arcRect, 180f, 180f * fraction, false, arcPaint)
        }

        // Skalenstriche (11 Stueck)
        val cx = w / 2f
        val cy = arcRect.centerY()
        val radius = arcRect.width() / 2f
        for (i in 0..10) {
            val angle = Math.toRadians(180.0 + 18.0 * i)
            val inner = radius - stroke * 0.85f
            val outer = radius - stroke * 0.25f
            val x1 = cx + (inner * Math.cos(angle)).toFloat()
            val y1 = cy + (inner * Math.sin(angle)).toFloat()
            val x2 = cx + (outer * Math.cos(angle)).toFloat()
            val y2 = cy + (outer * Math.sin(angle)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        // Wert
        valuePaint.textSize = min(w, h) * 0.22f
        unitPaint.textSize = min(w, h) * 0.085f
        labelPaint.textSize = min(w, h) * 0.055f
        demoPaint.textSize = min(w, h) * 0.055f

        val valueText = if (available && value != null) {
            if (value!! >= 100f) String.format(java.util.Locale.US, "%.0f", value!!)
            else String.format(java.util.Locale.US, "%.1f", value!!)
        } else "--"

        val valueY = cy + h * 0.02f
        canvas.drawText(valueText, cx, valueY, valuePaint)
        canvas.drawText(if (available) unit else "Nicht verfuegbar", cx, valueY + unitPaint.textSize * 1.6f, unitPaint)
        canvas.drawText(label, cx, valueY + unitPaint.textSize * 1.6f + labelPaint.textSize * 2.0f, labelPaint)

        if (demo) {
            canvas.drawText("DEMO", cx, valueY - valuePaint.textSize * 0.95f, demoPaint)
        }
    }
}

/**
 * Kleiner Verlaufsgraph fuer Messwert-Historien.
 * Zeichnet nur tatsaechlich uebergebene Werte; ohne Daten erscheint
 * ein Hinweis statt einer erfundenen Kurve.
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var values: List<Float> = emptyList()
    private var maxValue: Float = 1f
    private var accent: Int = Palette.ACCENT
    private var emptyText: String = "Noch keine Messwerte"

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Palette.GRID_LINE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT_MUTED
        textAlign = Paint.Align.CENTER
    }

    private val path = Path()
    private val fillPath = Path()

    fun setValues(values: List<Float>, maxValue: Float, accent: Int = Palette.ACCENT, emptyText: String = "Noch keine Messwerte") {
        this.values = values
        this.maxValue = if (maxValue > 0f) maxValue else 1f
        this.accent = accent
        this.emptyText = emptyText
        linePaint.color = accent
        fillPaint.color = Color.argb(48, Color.red(accent), Color.green(accent), Color.blue(accent))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Raster
        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        if (values.size < 2) {
            textPaint.textSize = min(w, h) * 0.16f
            canvas.drawText(emptyText, w / 2f, h / 2f + textPaint.textSize / 3f, textPaint)
            return
        }

        val stepX = w / (values.size - 1).toFloat()
        path.reset()
        fillPath.reset()

        values.forEachIndexed { index, v ->
            val x = index * stepX
            val norm = (v / maxValue).coerceIn(0f, 1f)
            val y = h - (norm * (h - 6f)) - 3f
            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(w, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}
