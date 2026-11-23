package com.jarvis.asistente

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** El "reactor" animado de Jarvis: cambia de color y late según el estado y la voz. */
class OrbView(context: Context) : View(context) {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    var state: State = State.IDLE
        set(value) {
            field = value
            invalidate()
        }

    private var level = 0f
    private var target = 0f
    private var phase = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    fun setLevel(rmsDb: Float) {
        target = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
    }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) * 0.24f
        phase += 0.035f
        if (state != State.LISTENING) target = 0f
        level += (target - level) * 0.25f

        val color = when (state) {
            State.LISTENING -> 0xFF00E5FF.toInt()
            State.THINKING -> 0xFFFFC400.toInt()
            State.SPEAKING -> 0xFF40C4FF.toInt()
            State.IDLE -> 0xFF0091EA.toInt()
        }
        val pulse = when (state) {
            State.LISTENING -> 0.04f + level * 0.35f
            State.THINKING -> 0.06f * sin(phase * 4f)
            State.SPEAKING -> 0.05f + 0.07f * abs(sin(phase * 5f))
            State.IDLE -> 0.025f * sin(phase * 1.5f)
        }
        val r = base * (1f + pulse)

        fill.shader = RadialGradient(
            cx, cy, r * 1.9f,
            intArrayOf(withAlpha(color, 110), withAlpha(color, 30), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 1.9f, fill)
        fill.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.WHITE, color, withAlpha(color, 60)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fill)
        fill.shader = null

        val d = resources.displayMetrics.density
        val speed = if (state == State.THINKING) 240f else 60f
        for (k in 0 until 3) {
            val rr = r * (1.25f + k * 0.18f)
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            stroke.strokeWidth = (3 - k) * d + d
            stroke.color = withAlpha(color, 200 - k * 50)
            val dir = if (k % 2 == 0) 1f else -1f
            val start = (phase * speed * dir + k * 120f) % 360f
            canvas.drawArc(rect, start, 80f + k * 20f, false, stroke)
            canvas.drawArc(rect, start + 180f, 40f, false, stroke)
        }
        postInvalidateOnAnimation()
    }
}
