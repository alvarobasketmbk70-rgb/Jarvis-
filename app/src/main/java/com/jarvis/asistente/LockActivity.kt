package com.jarvis.asistente

import android.app.Activity
import android.app.KeyguardManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Panel de JANUS sobre la pantalla de bloqueo (como Siri con el móvil bloqueado).
 * Enciende la pantalla, muestra el núcleo y lo que oye/responde. El servicio lo controla.
 */
class LockActivity : Activity() {

    companion object {
        @Volatile
        var current: LockActivity? = null
            private set
    }

    private lateinit var orb: OrbView
    private lateinit var label: TextView
    private lateinit var text: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = 0xFF05080F.toInt()
        window.navigationBarColor = 0xFF05080F.toInt()

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xF205080F.toInt())
            setOnClickListener { WakeWordService.instance?.cancelFromUi() }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), 0, dp(24), dp(64))
        }
        orb = OrbView(this)
        col.addView(orb, LinearLayout.LayoutParams(dp(260), dp(260)))
        label = TextView(this).apply {
            textSize = 12f
            letterSpacing = 0.25f
            setTextColor(0xFFFFB547.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        col.addView(label)
        text = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFFF4F1EA.toInt())
            gravity = Gravity.CENTER
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(10), 0, 0)
        }
        col.addView(text)
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        current = this
        WakeWordService.instance?.syncUi()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    fun show(state: OrbView.State, message: String?) {
        orb.state = state
        label.text = when (state) {
            OrbView.State.LISTENING -> "ESCUCHANDO"
            OrbView.State.THINKING -> "PROCESANDO"
            OrbView.State.SPEAKING -> "J.A.N.U.S."
            OrbView.State.IDLE -> ""
        }
        if (message != null) text.text = message
    }

    fun setText(s: String) { text.text = s }
    fun setLevel(db: Float) = orb.setLevel(db)

    /** Pide desbloquear (huella, cara o PIN) para las tareas que lo necesitan. */
    fun requestUnlock(cb: (Boolean) -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) { cb(true); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = cb(true)
            override fun onDismissCancelled() = cb(false)
            override fun onDismissError() = cb(false)
        })
    }
}
