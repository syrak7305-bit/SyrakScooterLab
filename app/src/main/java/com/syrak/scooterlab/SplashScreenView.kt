package com.syrak.scooterlab

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

/* ============================================================================
 *  PHASE 13 — PREMIUM MOTORSPORT SPLASH
 *  ----------------------------------------------------------------------------
 *  Reiner Canvas-Splash ohne zusaetzliche Bibliotheken:
 *    • tiefschwarzer/anthrazitfarbener Verlauf, dunkelrote Akzente
 *    • geometrische Scan-Linie, dezenter roter Lichtimpuls
 *    • Logo (Vektor) mit kurzer, kraftvoller Einblendung
 *    • segmentierte Fortschrittsanzeige + echte Statusmeldungen
 *
 *  WICHTIG:
 *    • Der Splash zeigt ausschliesslich Zustaende, die MainActivity ihm
 *      uebergibt (keine erfundenen Hardwarepruefungen).
 *    • Es gibt KEINE kuenstliche Mindestwartezeit — die Navigation haengt
 *      allein an den tatsaechlichen Initialisierungsschritten.
 *    • Der Animator wird in onDetachedFromWindow beendet (kein Leak).
 * ==========================================================================*/
class SplashScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(18, 236, 240, 244)
    }
    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(12, 236, 240, 244)
    }
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.DANGER
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val redFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.DANGER }
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textSize = dp(38f)
        letterSpacing = 0.10f
        color = Palette.TEXT_PRIMARY
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textSize = dp(13f)
        letterSpacing = 0.46f
        color = Palette.TEXT_SECONDARY
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("monospace", Typeface.NORMAL)
        textSize = dp(10.5f)
        letterSpacing = 0.06f
        color = Color.rgb(158, 168, 180)
    }
    private val statusAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("monospace", Typeface.BOLD)
        textSize = dp(10.5f)
        color = Palette.ACCENT
    }
    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("monospace", Typeface.BOLD)
        textSize = dp(11f)
        color = Palette.TEXT_PRIMARY
        textAlign = Paint.Align.RIGHT
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 236, 240, 244)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.DANGER }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 16, 18, 22)
        strokeWidth = dp(1f)
    }
    private val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("monospace", Typeface.NORMAL)
        textSize = dp(9f)
        letterSpacing = 0.12f
        color = Color.rgb(96, 106, 118)
    }

    private val logoRect = RectF()
    private val clipPath = Path()

    private var logo: android.graphics.drawable.Drawable? =
        ContextCompat.getDrawable(context, R.drawable.ic_syrak_mark)

    /** 0..1 Zyklus fuer Scan-Linie und Lichtimpuls. */
    private var phase = 0f
    private var progress = 0f
    private var percent = 0
    private var stageLabel = SplashSequence.initializing().label
    private var stageDetail = ""
    private var ready = false
    private var animator: ValueAnimator? = null

    init {
        setBackgroundColor(Palette.BG)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Syrak ScooterLab startet"
    }

    /** Uebergibt einen echten, soeben ausgefuehrten Startschritt. */
    fun setStage(stage: SplashStage, progress: Float, percent: Int) {
        stageLabel = stage.label
        stageDetail = stage.detail
        this.progress = progress.coerceIn(0f, 1f)
        this.percent = percent.coerceIn(0, 100)
        invalidate()
    }

    fun setReady() {
        ready = true
        stageLabel = SplashSequence.ready().label
        progress = 1f
        percent = 100
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimator()
    }

    override fun onDetachedFromWindow() {
        stopAnimator()
        super.onDetachedFromWindow()
    }

    private fun startAnimator() {
        if (animator != null) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            phase = 0.35f
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator?.removeAllUpdateListeners()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        drawBackground(canvas, w, h)
        drawHatch(canvas, w, h)

        val logoSize = minOf(w * 0.40f, dp(150f))
        val logoCx = w / 2f
        val logoCy = h * 0.30f
        drawLogoGlow(canvas, logoCx, logoCy, logoSize)
        drawScanLine(canvas, w, h)

        logoRect.set(
            logoCx - logoSize / 2f,
            logoCy - logoSize / 2f,
            logoCx + logoSize / 2f,
            logoCy + logoSize / 2f
        )
        logo?.let {
            it.setBounds(
                logoRect.left.toInt(), logoRect.top.toInt(),
                logoRect.right.toInt(), logoRect.bottom.toInt()
            )
            it.draw(canvas)
        }

        drawWordmark(canvas, w, logoCy + logoSize / 2f + dp(30f))
        drawProgress(canvas, w, h)
        drawStatus(canvas, w, h)
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        bgPaint.shader = LinearGradient(
            0f, 0f, w * 0.35f, h,
            intArrayOf(Color.rgb(9, 11, 14), Color.rgb(16, 18, 22), Color.rgb(22, 26, 32)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        bgPaint.shader = null

        // Feines technisches Raster im unteren Bereich
        var y = h * 0.62f
        while (y < h) {
            canvas.drawLine(0f, y, w, y, gridPaint)
            y += dp(34f)
        }
        var x = 0f
        while (x < w) {
            canvas.drawLine(x, h * 0.62f, x, h, gridPaint)
            x += dp(34f)
        }
    }

    private fun drawHatch(canvas: Canvas, w: Float, h: Float) {
        val step = dp(22f)
        var x = -h
        while (x < w) {
            canvas.drawLine(x, 0f, x + h * 0.45f, h * 0.45f, hatchPaint)
            x += step
        }
    }

    private fun drawLogoGlow(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val pulse = 0.5f + 0.5f * kotlin.math.sin(phase * 2f * Math.PI.toFloat())
        val radius = size * (0.72f + 0.10f * pulse)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    Color.argb((46 + 34 * pulse).toInt(), 193, 18, 32),
                    Color.argb(10, 193, 18, 32),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, radius, glow)

        // Zwei gegenlaeufige Bogen-Segmente als "Diagnose-Ring"
        redPaint.alpha = (70 + 60 * pulse).toInt().coerceIn(0, 255)
        redPaint.strokeWidth = dp(1.6f)
        val ring = RectF(cx - radius * 0.86f, cy - radius * 0.86f, cx + radius * 0.86f, cy + radius * 0.86f)
        val start = (phase * 360f) % 360f
        canvas.drawArc(ring, start, 54f, false, redPaint)
        canvas.drawArc(ring, start + 180f, 30f, false, redPaint)
        redPaint.alpha = 255
    }

    private fun drawScanLine(canvas: Canvas, w: Float, h: Float) {
        val top = h * 0.16f
        val span = h * 0.56f
        val y = top + span * phase
        val scan = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(60, 193, 18, 32),
                    Color.argb(150, 229, 57, 53),
                    Color.argb(60, 193, 18, 32),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.28f, 0.5f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, y - dp(0.8f), w, y + dp(0.8f), scan)

        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, dp(26f),
                intArrayOf(Color.argb(26, 229, 57, 53), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, y, w, y + dp(26f), halo)
    }

    private fun drawWordmark(canvas: Canvas, w: Float, top: Float) {
        val title = "SYRAK"
        val titleWidth = titlePaint.measureText(title)
        canvas.drawText(title, (w - titleWidth) / 2f, top + titlePaint.textSize * 0.85f, titlePaint)

        val sub = "SCOOTERLAB"
        val subWidth = subPaint.measureText(sub)
        val subY = top + titlePaint.textSize * 0.85f + dp(20f)
        canvas.drawText(sub, (w - subWidth) / 2f, subY, subPaint)

        // Technische Trennlinie mit Endmarken
        val lineY = subY + dp(16f)
        val half = minOf(w * 0.30f, dp(120f))
        canvas.drawLine(w / 2f - half, lineY, w / 2f + half, lineY, gridPaint)
        redFill.alpha = 220
        canvas.drawRect(w / 2f - dp(10f), lineY - dp(1f), w / 2f + dp(10f), lineY + dp(1f), redFill)
        redFill.alpha = 255
    }

    private fun drawProgress(canvas: Canvas, w: Float, h: Float) {
        val left = w * 0.10f
        val right = w * 0.90f
        val y = h * 0.795f
        val barH = dp(3f)

        canvas.drawRect(left, y, right, y + barH, trackPaint)
        canvas.drawRect(left, y, left + (right - left) * progress, y + barH, progressPaint)

        // Segmentmarken alle 10 %
        for (i in 1 until 10) {
            val x = left + (right - left) * (i / 10f)
            canvas.drawRect(x, y - dp(2f), x + dp(1f), y + barH + dp(2f), tickPaint)
        }
        // Feine Endkappe in Rot
        canvas.drawRect(right - dp(1f), y - dp(4f), right, y + barH + dp(4f), redFill)
    }

    private fun drawStatus(canvas: Canvas, w: Float, h: Float) {
        val left = w * 0.10f
        val right = w * 0.90f
        val labelY = h * 0.845f
        val blink = if (ready) 1f else (0.55f + 0.45f * kotlin.math.sin(phase * 2f * Math.PI.toFloat()))

        statusAccentPaint.alpha = (255 * blink).toInt().coerceIn(60, 255)
        canvas.drawText(if (ready) ">" else "›", left, labelY, statusAccentPaint)
        statusAccentPaint.alpha = 255
        canvas.drawText(stageLabel, left + dp(12f), labelY, statusPaint)

        percentPaint.color = if (ready) Palette.SUCCESS else Palette.TEXT_PRIMARY
        canvas.drawText("$percent%", right, labelY, percentPaint)

        if (stageDetail.isNotBlank()) {
            canvas.drawText(stageDetail, left, labelY + dp(16f), footerPaint)
        }

        canvas.drawText("DIAGNOSTIC CORE  ·  PHASE 13", left, h - dp(26f), footerPaint)
        val v = "v1.0"
        canvas.drawText(v, right - footerPaint.measureText(v), h - dp(26f), footerPaint)
    }
}
