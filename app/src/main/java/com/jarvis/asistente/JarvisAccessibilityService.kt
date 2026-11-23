package com.jarvis.asistente

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * Permite a Jarvis "ver" y tocar la pantalla: pulsar botones por su texto,
 * desplazarse, escribir, leer y pulsar automáticamente "Enviar" en WhatsApp.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        @Volatile
        private var waSendUntil = 0L

        fun armWhatsAppSend() {
            waSendUntil = System.currentTimeMillis() + 20_000
        }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (waSendUntil == 0L) return
        if (System.currentTimeMillis() > waSendUntil) {
            waSendUntil = 0L
            return
        }
        val pkg = event?.packageName?.toString() ?: return
        if (!pkg.startsWith("com.whatsapp")) return
        val root = rootInActiveWindow ?: return
        val byId = root.findAccessibilityNodeInfosByViewId("$pkg:id/send")
        val node = byId?.firstOrNull { it.isVisibleToUser } ?: findNode(root) { n ->
            val d = n.contentDescription?.toString()?.lowercase(ES) ?: ""
            (d == "enviar" || d == "send") && n.isVisibleToUser
        }
        if (node != null) {
            waSendUntil = 0L
            main.postDelayed({ click(node) }, 300)
        }
    }

    // ---------- Utilidades de árbol ----------
    private fun findNode(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val q = java.util.ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var count = 0
        while (q.isNotEmpty() && count < 3000) {
            val n = q.poll() ?: break
            count++
            if (pred(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun allNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        findNode(root) { out.add(it); false }
        return out
    }

    /** Ventanas visibles de otras apps (nunca la propia de Jarvis). */
    private fun roots(): List<AccessibilityNodeInfo> {
        val list = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { list.add(it) }
        try {
            for (w in windows) w.root?.let { r -> if (list.none { it == r }) list.add(r) }
        } catch (_: Exception) {
        }
        return list.filter { it.packageName?.toString() != packageName }
    }

    // ---------- Acciones ----------
    fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    fun clickText(query: String): Boolean {
        val q = TextUtil.norm(query)
        if (q.isEmpty()) return false
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0.0
        for (root in roots()) {
            for (n in allNodes(root)) {
                if (!n.isVisibleToUser) continue
                for (label in listOf(n.text, n.contentDescription, n.hintText)) {
                    if (label.isNullOrBlank()) continue
                    val l = TextUtil.norm(label.toString())
                    val score = TextUtil.matchScore(q, l) - abs(l.length - q.length) * 0.001
                    if (score > bestScore) {
                        bestScore = score
                        best = n
                    }
                }
            }
        }
        val b = best ?: return false
        if (bestScore < 0.6) return false
        return click(b)
    }

    fun tap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        return dispatchGesture(g, null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 350))
            .build()
        return dispatchGesture(g, null, null)
    }

    fun scroll(direction: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        return when (direction) {
            "up" -> swipe(w / 2, h * 0.30f, w / 2, h * 0.75f)
            "left" -> swipe(w * 0.85f, h / 2, w * 0.15f, h / 2)
            "right" -> swipe(w * 0.15f, h / 2, w * 0.85f, h / 2)
            else -> swipe(w / 2, h * 0.75f, w / 2, h * 0.30f)
        }
    }

    fun global(cmd: String): Boolean {
        val action = when (cmd) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return false
            else -> return false
        }
        return performGlobalAction(action)
    }

    fun typeText(text: String): Boolean {
        for (root in roots()) {
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                ?: findNode(root) { it.isEditable && it.isVisibleToUser }
                ?: continue
            if (!target.isFocused) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        }
        return false
    }

    fun readScreen(): String {
        val seen = LinkedHashSet<String>()
        for (root in roots()) {
            for (n in allNodes(root)) {
                if (!n.isVisibleToUser) continue
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()
                if (!t.isNullOrEmpty() && t.length > 1) seen.add(t)
            }
        }
        val s = seen.joinToString(". ")
        return if (s.length > 600) s.take(600) + "…" else s
    }
}
