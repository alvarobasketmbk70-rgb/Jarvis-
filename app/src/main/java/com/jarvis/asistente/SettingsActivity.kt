package com.jarvis.asistente

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class SettingsActivity : Activity() {
    private val bg = 0xFF020B14.toInt()
    private val accent = 0xFF00E5FF.toInt()

    private lateinit var prefs: Prefs
    private lateinit var etKey: EditText
    private lateinit var etModel: EditText
    private lateinit var etName: EditText
    private lateinit var etCc: EditText
    private lateinit var swConv: Switch
    private lateinit var swWa: Switch
    private lateinit var swWake: Switch
    private lateinit var spNeural: Spinner
    private lateinit var swNeural: Switch
    private lateinit var swFx: Switch
    private lateinit var spPitch: Spinner
    private val pitches = listOf(1.0f to "Tono normal", 0.94f to "Algo más grave (recomendado)", 0.88f to "Grave", 0.82f to "Muy grave")
    private var speaker: Speaker? = null
    private lateinit var tvAcc: TextView
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        prefs = Prefs(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(48))
        }
        val sv = ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(col)
        }

        col.addView(text("Ajustes de Janus", 24f, accent))

        col.addView(header("Cerebro (inteligencia artificial)"))
        col.addView(note("Los comandos básicos funcionan sin esto. Para conversar libremente, Janus usa una IA en la nube: pegue una clave gratuita de Google Gemini (aistudio.google.com/apikey) o una de Claude (empieza por sk-ant-)."))
        etKey = makeField("Clave de API", prefs.apiKey, secret = true); col.addView(etKey)
        etModel = makeField("Modelo (vacío = automático)", prefs.model); col.addView(etModel)

        col.addView(header("Personalización"))
        etName = makeField("¿Cómo quiere que le llame? (señor, Tony…)", if (prefs.userName == "señor") "" else prefs.userName); col.addView(etName)
        etCc = makeField("Prefijo de país para WhatsApp (34 = España)", prefs.countryCode, number = true); col.addView(etCc)
        col.addView(header("Voz de Janus"))
        col.addView(note("Janus tiene una única voz, diseñada al estilo del asistente de la película. Necesita la clave de Gemini e internet; sin ellas no puede hablar (no usa ninguna otra voz)."))
        col.addView(button("Probar voz") { testVoice() })
        swConv = switch("Modo conversación: sigue escuchando después de responder", prefs.conversationMode); col.addView(swConv)
        swWa = switch("Enviar WhatsApp automáticamente (requiere accesibilidad)", prefs.autoSendWhatsApp); col.addView(swWa)
        swWake = switch("Responder a «Oye Janus» con la app cerrada (con la pantalla encendida; gasta algo de batería)", prefs.wakeWord); col.addView(swWake)
        swWake.setOnCheckedChangeListener { _, on ->
            if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                toast("Primero conceda el permiso de micrófono abriendo Janus.")
                swWake.isChecked = false
                return@setOnCheckedChangeListener
            }
            prefs.wakeWord = on
            if (on) WakeWordService.start(this) else WakeWordService.stop(this)
        }

        col.addView(header("Permisos del sistema (Xiaomi / HyperOS)"))
        tvAcc = note(""); col.addView(tvAcc)
        col.addView(button("1. Info de la app → ⋮ → Permitir ajustes restringidos") { openAppDetails() })
        col.addView(button("2. Activar servicio de accesibilidad") {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Busque «Janus» (en Aplicaciones descargadas) y actívelo.")
        })
        col.addView(button("3. Mostrar sobre otras apps") {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(button("4. Otros permisos Xiaomi (ventanas en segundo plano)") { openMiuiPerms() })
        col.addView(button("5. Sin restricciones de batería") { requestBattery() })
        col.addView(button("6. Poner Janus como asistente predeterminado") { openDefaultAssistant() })
        col.addView(note("Con el paso 6, al mantener pulsado el botón de encendido (o deslizar desde una esquina inferior) se abrirá Janus escuchando. También puede añadir el botón «Janus» en los ajustes rápidos."))

        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        tvAcc.text = if (JarvisAccessibilityService.instance != null) "✅ Accesibilidad activada"
        else "⚠️ Accesibilidad desactivada: es necesaria para pulsar botones, desplazarse, leer la pantalla y enviar WhatsApp automáticamente."
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onDestroy() {
        tts?.shutdown()
        speaker?.shutdown()
        super.onDestroy()
    }

    private fun save() {
        prefs.apiKey = etKey.text.toString()
        prefs.model = etModel.text.toString()
        prefs.userName = etName.text.toString()
        prefs.countryCode = etCc.text.toString()
        prefs.conversationMode = swConv.isChecked
        prefs.autoSendWhatsApp = swWa.isChecked
    }

    // ---------- Voz ----------
    private fun testVoice() {
        save()
        val sp = speaker ?: Speaker(this).also { s ->
            speaker = s
            s.onVoiceError = { reason -> toast("No se pudo generar la voz: $reason") }
        }
        val name = prefs.userName
        val key = prefs.apiKey
        if (key.isNotBlank() && !key.startsWith("sk-ant-")) toast("Generando la voz de JANUS…")
        sp.stop()
        sp.speak("A su servicio, $name. Soy JANUS. Todos los sistemas están operativos.")
    }

    // ---------- Accesos a ajustes del sistema ----------
    private fun open(i: Intent): Boolean = try {
        startActivity(i); true
    } catch (e: Exception) {
        false
    }

    private fun openAppDetails() {
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        toast("Si la accesibilidad sale bloqueada: menú ⋮ arriba a la derecha → «Permitir ajustes restringidos». Active también «Inicio automático».")
    }

    private fun openMiuiPerms() {
        val i = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", packageName)
        if (open(i)) toast("Active «Mostrar ventanas emergentes mientras se ejecuta en segundo plano».")
        else openAppDetails()
    }

    private fun requestBattery() {
        val ok = open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        if (!ok) open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun openDefaultAssistant() {
        val ok = open(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) ||
            open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) ||
            open(Intent(Settings.ACTION_SETTINGS))
        if (ok) toast("Entre en «Asistente digital» o «App de asistencia» y elija Janus.")
    }

    // ---------- Widgets ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun text(s: String, size: Float, color: Int) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun header(s: String) = text(s, 17f, accent).apply { setPadding(0, dp(22), 0, dp(6)) }

    private fun note(s: String) = text(s, 13f, 0xFF90A4AE.toInt())

    private fun makeField(hint: String, value: String, secret: Boolean = false, number: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF4A6572.toInt())
        setSingleLine(true)
        inputType = when {
            number -> InputType.TYPE_CLASS_NUMBER
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else -> InputType.TYPE_CLASS_TEXT
        }
    }

    private fun switch(label: String, value: Boolean) = Switch(this).apply {
        text = label
        isChecked = value
        setTextColor(Color.WHITE)
        setPadding(0, dp(10), 0, dp(10))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        }
    }
}
