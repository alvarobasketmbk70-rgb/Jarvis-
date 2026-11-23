package com.jarvis.asistente

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * El "núcleo" de Janus: una estrella dorada con rayos, polvo de partículas, una membrana
 * que ondula y un anillo exterior de pulsos que reacciona a la voz.
 */
class OrbView(context: Context) : View(context) {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    var state: State = State.IDLE
        set(value) {
            field = value
            invalidate()
        }

    private companion object {
        const val GOLD = 0xFFFFB547.toInt()
        const val ORANGE = 0xFFFF8A1F.toInt()
        const val RED = 0xFFFF4D4D.toInt()
        const val WHITE = 0xFFF4F1EA.toInt()
        const val TWO_PI = (2 * PI).toFloat()
        const val N_P = 170
        const val N_R = 80
        const val N_T = 120
    }

    private var level = 0f
    private var target = 0f
    private var phase = 0f
    private val rnd = Random(7)

    private val pAng = FloatArray(N_P) { rnd.nextFloat() * TWO_PI }
    private val pRad = FloatArray(N_P) { rnd.nextFloat() }
    private val pSize = FloatArray(N_P) { 0.5f + rnd.nextFloat() * 1.6f }
    private val pSpeed = FloatArray(N_P) { (0.002f + rnd.nextFloat() * 0.006f) * (if (rnd.nextBoolean()) 1f else -1f) }
    private val pCol = IntArray(N_P) {
        when (rnd.nextInt(10)) {
            0, 1 -> RED
            2, 3 -> WHITE
            4 -> ORANGE
            else -> GOLD
        }
    }
    private val rayLen = FloatArray(N_R) { 0.3f + rnd.nextFloat() }
    private val tickSeed = FloatArray(N_T) { rnd.nextFloat() * TWO_PI }

    private val rayLines = FloatArray(N_R * 4)
    private val whiteTicks = FloatArray(N_T * 4)
    private val redTicks = FloatArray(N_T * 4)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val rect = RectF()

    fun setLevel(rmsDb: Float) {
        target = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
    }

    private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val size = min(width, height).toFloat()
        val r = size * 0.075f
        phase += 0.016f

        when (state) {
            State.SPEAKING -> target = 0.25f + 0.55f * abs(sin(phase * 9f)) * abs(sin(phase * 3.7f + 1f))
            State.THINKING -> target = 0.25f + 0.15f * abs(sin(phase * 5f))
            State.IDLE -> target = 0f
            State.LISTENING -> {}
        }
        level += (target - level) * 0.22f

        val spin = when (state) {
            State.THINKING -> 4f
            State.LISTENING -> 1.3f
            State.SPEAKING -> 1.6f
            State.IDLE -> 0.5f
        }
        val bright = when (state) {
            State.IDLE -> 0.65f
            else -> 1f
        }

        // Halo cálido
        val glowR = r * (4.2f + level * 1.5f)
        fill.shader = RadialGradient(
            cx, cy, glowR,
            intArrayOf(alpha(ORANGE, (150 * bright).toInt()), alpha(ORANGE, (45 * bright).toInt()), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowR, fill)

        // Rayos de la estrella
        for (i in 0 until N_R) {
            val a = i * TWO_PI / N_R + phase * 0.25f * spin
            val len = r * (1.3f + rayLen[i] * (1.4f + level * 2.6f))
            val c = cos(a); val s = sin(a)
            rayLines[i * 4] = cx + c * r * 0.8f
            rayLines[i * 4 + 1] = cy + s * r * 0.8f
            rayLines[i * 4 + 2] = cx + c * len
            rayLines[i * 4 + 3] = cy + s * len
        }
        line.strokeWidth = 0.8f * d
        line.color = alpha(GOLD, (110 * bright).toInt())
        canvas.drawLines(rayLines, line)

        // Núcleo
        val coreR = r * (1.5f + level * 0.35f)
        fill.shader = RadialGradient(
            cx, cy, coreR,
            intArrayOf(Color.WHITE, 0xFFFFE29A.toInt(), alpha(ORANGE, 230), Color.TRANSPARENT),
            floatArrayOf(0f, 0.25f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreR, fill)
        fill.shader = null

        // Órbitas doradas
        line.strokeWidth = 1.2f * d
        for (k in 0 until 3) {
            val rx = size * (0.20f + k * 0.045f)
            val ry = rx * (0.92f - k * 0.05f)
            canvas.save()
            canvas.rotate((phase * 18f * spin * (if (k % 2 == 0) 1 else -1) + k * 60f) % 360f, cx, cy)
            rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
            line.color = alpha(GOLD, ((170 - k * 40) * bright).toInt())
            canvas.drawArc(rect, 200f, 150f + k * 30f, false, line)
            canvas.restore()
        }

        // Membrana ondulante
        val rm = size * 0.33f
        path.reset()
        val steps = 140
        for (i in 0..steps) {
            val a = i * TWO_PI / steps
            val k = 1f + 0.035f * sin(6 * a + phase * 2f) + 0.025f * sin(11 * a - phase * 3f) +
                level * 0.06f * sin(17 * a + phase * 6f)
            val x = cx + cos(a) * rm * k
            val y = cy + sin(a) * rm * k
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        line.strokeWidth = 1f * d
        line.color = alpha(0xFFFF6B3D.toInt(), (170 * bright).toInt())
        canvas.drawPath(path, line)

        // Polvo de partículas
        val inner = r * 1.6f
        val outer = size * 0.37f
        for (i in 0 until N_P) {
            pAng[i] += pSpeed[i] * spin
            val rr = inner + pRad[i] * (outer - inner) * (1f + level * 0.08f * sin(phase * 4 + i))
            dot.color = alpha(pCol[i], (200 * bright).toInt())
            canvas.drawCircle(cx + cos(pAng[i]) * rr, cy + sin(pAng[i]) * rr, pSize[i] * d * 0.7f, dot)
        }

        // Anillo exterior de pulsos (blanco a la izquierda, rojo a la derecha)
        val rt = size * 0.41f
        var nw = 0
        var nr = 0
        for (i in 0 until N_T) {
            val a = i * TWO_PI / N_T - (PI / 2).toFloat() + phase * 0.05f * spin
            val wave = 0.3f + 0.7f * abs(sin(tickSeed[i] + phase * 3f))
            val len = d * (3f + wave * (5f + level * 16f))
            val c = cos(a); val s = sin(a)
            val arr: FloatArray
            val idx: Int
            if (c > 0.25f && s > -0.6f) {
                arr = redTicks; idx = nr; nr++
            } else {
                arr = whiteTicks; idx = nw; nw++
            }
            arr[idx * 4] = cx + c * rt
            arr[idx * 4 + 1] = cy + s * rt
            arr[idx * 4 + 2] = cx + c * (rt + len)
            arr[idx * 4 + 3] = cy + s * (rt + len)
        }
        line.strokeWidth = 2.2f * d
        line.color = alpha(WHITE, (230 * bright).toInt())
        canvas.drawLines(whiteTicks, 0, nw * 4, line)
        line.color = alpha(RED, (230 * bright).toInt())
        canvas.drawLines(redTicks, 0, nr * 4, line)

        // Fino anillo de referencia
        line.strokeWidth = 0.8f * d
        line.color = alpha(GOLD, (70 * bright).toInt())
        canvas.drawCircle(cx, cy, rt - 6 * d, line)

        postInvalidateOnAnimation()
    }
}
