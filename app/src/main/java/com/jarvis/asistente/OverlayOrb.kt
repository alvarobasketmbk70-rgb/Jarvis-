package com.jarvis.asistente

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Orbe flotante que aparece sobre cualquier app al decir «Oye Jarvis» (estilo Siri). */
class OverlayOrb(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var orb: OrbView? = null
    private var label: TextView? = null
    private var text: TextView? = null
    var onTap: (() -> Unit)? = null

    val isShown: Boolean get() = root != null

    fun canShow(): Boolean = Settings.canDrawOverlays(ctx)

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show(state: OrbView.State, message: String? = null) {
        if (!canShow()) return
        if (root == null && !build()) return
        orb?.state = state
        label?.text = when (state) {
            OrbView.State.LISTENING -> "ESCUCHANDO"
            OrbView.State.THINKING -> "PROCESANDO"
            OrbView.State.SPEAKING -> "J.A.R.V.I.S."
            OrbView.State.IDLE -> ""
        }
        if (message != null) setText(message)
    }

    fun setText(s: String) {
        text?.text = s
    }

    fun setLevel(rmsDb: Float) {
        orb?.setLevel(rmsDb)
    }

    private fun build(): Boolean {
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(18))
            background = GradientDrawable().apply {
                setColor(0xEE070B14.toInt())
                cornerRadius = dp(32).toFloat()
                setStroke(dp(1), 0x55FFB547)
            }
            setOnClickListener { onTap?.invoke() }
        }
        val o = OrbView(ctx)
        panel.addView(o, LinearLayout.LayoutParams(dp(230), dp(230)))
        val l = TextView(ctx).apply {
            textSize = 11f
            letterSpacing = 0.25f
            setTextColor(0xFFFFB547.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        panel.addView(l)
        val t = TextView(ctx).apply {
            textSize = 15f
            setTextColor(0xFFF4F1EA.toInt())
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }
        panel.addView(t)

        val params = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(56)
        }
        return try {
            panel.alpha = 0f
            panel.translationY = dp(40).toFloat()
            wm.addView(panel, params)
            panel.animate().alpha(1f).translationY(0f).setDuration(220).start()
            root = panel; orb = o; label = l; text = t
            true
        } catch (e: Exception) {
            false
        }
    }

    fun hide() {
        val r = root ?: return
        root = null; orb = null; label = null; text = null
        r.animate().alpha(0f).translationY(dp(40).toFloat()).setDuration(200).withEndAction {
            try {
                wm.removeView(r)
            } catch (_: Exception) {
            }
        }.start()
    }
}
