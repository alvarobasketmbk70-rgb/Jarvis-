package com.jarvis.asistente

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.sin

/** Brillo dorado que recorre los bordes de la pantalla mientras Janus atiende (como Siri). */
class EdgeGlowView(context: Context) : View(context) {
    private var level = 0f
    private var target = 0f
    private var phase = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val matrix = Matrix()
    private var shader: SweepGradient? = null
    private var sw = 0
    private var sh = 0
    var speaking = false

    fun setLevel(rmsDb: Float) {
        target = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        if (shader == null || w != sw || h != sh) {
            sw = w; sh = h
            shader = SweepGradient(
                w / 2f, h / 2f,
                intArrayOf(
                    0xFFFFB547.toInt(), 0xFFFF8A1F.toInt(), 0xFFFF4D4D.toInt(),
                    0xFFFFE29A.toInt(), 0xFFFF8A1F.toInt(), 0xFFFFB547.toInt()
                ),
                null
            )
        }
        phase += 0.02f
        if (speaking) target = 0.3f + 0.5f * kotlin.math.abs(sin(phase * 7f))
        level += (target - level) * 0.2f
        matrix.setRotate((phase * 60f) % 360f, w / 2f, h / 2f)
        shader?.setLocalMatrix(matrix)
        paint.shader = shader
        val d = resources.displayMetrics.density
        val corner = 38f * d
        for (k in 0 until 5) {
            val stroke = (3f + k * 6f) * d * (0.8f + level * 0.7f)
            paint.strokeWidth = stroke
            paint.alpha = (210 / (k + 1)).coerceIn(0, 255)
            rect.set(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f)
            canvas.drawRoundRect(rect, corner, corner, paint)
        }
        postInvalidateOnAnimation()
    }
}
