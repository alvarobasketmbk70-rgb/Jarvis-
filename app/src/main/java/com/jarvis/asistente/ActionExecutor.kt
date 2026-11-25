package com.jarvis.asistente

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import java.util.Calendar
import kotlin.math.roundToInt

data class ExecResult(val ok: Boolean, val text: String, val informative: Boolean = false)

class ActionExecutor(context: Context) {
    private val ctx = context
    private val prefs = Prefs(context)
    private val main = Handler(Looper.getMainLooper())

    companion object {
        const val SPOTIFY = "com.spotify.music"
        const val YOUTUBE = "com.google.android.youtube"
        val SCREEN_TYPES = setOf("click", "scroll", "global", "type_text", "read_screen")

        /** Qué decir: lo de la IA si todo fue bien; si no, el resultado real de la acción. */
        fun choose(reply: Reply, r: ExecResult, name: String = "señor"): String {
            val say = reply.text
            if (!r.ok || r.informative) return r.text
            if (say.isNullOrBlank()) return Personality.confirm(r.text, name)
            return say
        }
    }

    fun execute(a: Action): ExecResult {
        val r = try {
            run(a)
        } catch (e: SecurityException) {
            ExecResult(false, "Me falta un permiso para hacer eso. Revise los permisos de Janus en ajustes.")
        } catch (e: Exception) {
            ExecResult(false, "Ha ocurrido un error: ${e.message ?: "desconocido"}.")
        }
        Diag.log("Acción ${a.type} ${a.args} → ${if (r.ok) "OK" else "FALLO"}: ${r.text}")
        return r
    }

    private fun run(a: Action): ExecResult = when (a.type) {
        "open_app" -> openApp(a.arg("app"))
        "call" -> call(a.arg("contact"))
        "whatsapp" -> whatsapp(a.arg("contact"), a.arg("message"))
        "sms" -> sms(a.arg("contact"), a.arg("message"))
        "media" -> media(a.arg("cmd"))
        "spotify_play" -> spotifyPlay(a.arg("query"))
        "youtube" -> youtube(a.arg("query"))
        "alarm" -> alarm(a.arg("hour").toIntOrNull(), a.arg("minute").toIntOrNull() ?: 0)
        "alarms_open" -> if (start(Intent(AlarmClock.ACTION_SHOW_ALARMS))) ExecResult(true, "Le abro sus alarmas.") else ExecResult(false, "No encuentro la app de reloj.")
        "timer" -> timer(a.arg("seconds").toIntOrNull() ?: 0)
        "flashlight" -> flashlight(a.arg("on") != "false")
        "volume" -> volume(a.arg("cmd"), a.arg("level").toIntOrNull())
        "time" -> time()
        "date" -> ExecResult(true, "Hoy es " + java.text.SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", ES).format(java.util.Date()) + ".", true)
        "battery" -> battery()
        "web_search" -> webSearch(a.arg("query"))
        "navigate" -> navigate(a.arg("destination"))
        "maps_search" -> mapsSearch(a.arg("query"))
        "wifi" -> {
            val i = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
            start(i); ExecResult(true, "Le abro el panel de wifi. Android no permite a las apps cambiarlo directamente.")
        }
        "bluetooth" -> { start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); ExecResult(true, "Le abro los ajustes de bluetooth.") }
        "airplane" -> { start(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)); ExecResult(true, "Le abro el modo avión.") }
        "jarvis_settings" -> { start(Intent(ctx, SettingsActivity::class.java)); ExecResult(true, "Abriendo mis ajustes.") }
        in SCREEN_TYPES -> screen(a)
        else -> ExecResult(false, "Todavía no sé hacer eso.")
    }

    fun start(i: Intent): Boolean = Launcher.start(ctx, i)

    private fun notFound(who: String) =
        if (!Contacts.hasPermission(ctx)) ExecResult(false, "Necesito permiso para leer sus contactos. Ábrame y concédamelo.")
        else ExecResult(false, "No encuentro a $who en sus contactos.")

    // ---------- Apps ----------
    private fun openApp(name: String): ExecResult {
        if (name.isBlank()) return ExecResult(false, "¿Qué aplicación quiere que abra?")
        val n = TextUtil.norm(name)
        if (n in setOf("ajustes", "configuracion", "ajustes del movil", "ajustes del telefono", "la configuracion")) {
            start(Intent(Settings.ACTION_SETTINGS)); return ExecResult(true, "Abriendo ajustes.")
        }
        val found = Apps.find(ctx, name)
        if (found == null) {
            if (n.contains("camara") && start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) return ExecResult(true, "Abriendo la cámara.")
            return ExecResult(false, "No encuentro ninguna aplicación llamada $name.")
        }
        start(found.second)
        return ExecResult(true, "Abriendo ${found.first}.")
    }

    // ---------- Llamadas y mensajes ----------
    private fun call(who: String): ExecResult {
        if (who.isBlank()) return ExecResult(false, "¿A quién quiere llamar?")
        val c = Contacts.find(ctx, who) ?: return notFound(who)
        val uri = Uri.parse("tel:" + Uri.encode(c.number))
        val granted = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        start(Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri))
        return ExecResult(true, "Llamando a ${c.name}.")
    }

    private fun whatsapp(who: String, msg: String): ExecResult {
        if (who.isBlank()) return ExecResult(false, "¿A quién se lo envío?")
        val c = Contacts.find(ctx, who) ?: return notFound(who)
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { Apps.isInstalled(ctx, it) }
            ?: return ExecResult(false, "WhatsApp no está instalado.")
        val phone = Contacts.toInternational(c.number, prefs.countryCode)
        val url = "https://api.whatsapp.com/send?phone=$phone&text=" + Uri.encode(msg)
        val auto = prefs.autoSendWhatsApp && msg.isNotBlank() && JarvisAccessibilityService.instance != null
        if (auto) JarvisAccessibilityService.armWhatsAppSend()
        if (!start(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg))) {
            return ExecResult(false, "No he podido abrir WhatsApp.")
        }
        return ExecResult(
            true,
            if (auto) "Enviando mensaje a ${c.name}."
            else "He abierto el chat con ${c.name} con el mensaje escrito. Solo tiene que pulsar enviar."
        )
    }

    private fun sms(who: String, msg: String): ExecResult {
        val c = Contacts.find(ctx, who) ?: return notFound(who)
        start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number))).putExtra("sms_body", msg))
        return ExecResult(true, "He preparado el SMS para ${c.name}.")
    }

    // ---------- Música ----------
    private fun media(cmd: String): ExecResult {
        val (code, text) = when (cmd) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE to "Música en pausa."
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY to "Reanudando la música."
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT to "Siguiente canción."
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Canción anterior."
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "Hecho."
        }
        // Se envía la tecla y se comprueba que la música hizo caso (si no, se repite).
        MediaControl.send(ctx, cmd)
        return ExecResult(true, text)
    }

    private fun spotifyPlay(q: String): ExecResult {
        if (!Apps.isInstalled(ctx, SPOTIFY)) return ExecResult(false, "Spotify no está instalado.")
        if (q.isBlank()) return media("play")
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(SPOTIFY)
            .putExtra(SearchManager.QUERY, q)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (!start(i)) start(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q))).setPackage(SPOTIFY))
        return ExecResult(true, "Poniendo $q en Spotify.")
    }

    private fun youtube(q: String): ExecResult {
        val i = Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE).putExtra("query", q)
        if (!start(i)) start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
        return ExecResult(true, "Buscando $q en YouTube.")
    }

    // ---------- Reloj ----------
    private fun alarm(h: Int?, m: Int): ExecResult {
        if (h == null) return ExecResult(false, "¿A qué hora pongo la alarma?")
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Janus")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!start(i)) return ExecResult(false, "No encuentro la app de reloj.")
        return ExecResult(true, String.format(ES, "Alarma puesta a las %d:%02d.", h, m))
    }

    private fun timer(seconds: Int): ExecResult {
        if (seconds <= 0) return ExecResult(false, "¿De cuánto tiempo pongo el temporizador?")
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Janus")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!start(i)) return ExecResult(false, "No encuentro la app de reloj.")
        return ExecResult(true, "Temporizador de ${describe(seconds)} en marcha.")
    }

    private fun describe(s: Int): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val parts = ArrayList<String>()
        if (h > 0) parts.add(if (h == 1) "1 hora" else "$h horas")
        if (m > 0) parts.add(if (m == 1) "1 minuto" else "$m minutos")
        if (sec > 0) parts.add(if (sec == 1) "1 segundo" else "$sec segundos")
        return parts.joinToString(" y ")
    }

    private fun time(): ExecResult {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        val hs = String.format(ES, "%d:%02d", h, m)
        return ExecResult(true, if (h == 1) "Es la $hs." else "Son las $hs.", true)
    }

    // ---------- Hardware ----------
    private fun flashlight(on: Boolean): ExecResult {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ExecResult(false, "Este móvil no tiene linterna disponible.")
        cm.setTorchMode(id, on)
        return ExecResult(true, if (on) "Linterna encendida." else "Linterna apagada.")
    }

    private fun volume(cmd: String, level: Int?): ExecResult {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(s)
        if (level != null) {
            val l = level.coerceIn(0, 100)
            am.setStreamVolume(s, (max * l / 100.0).roundToInt(), AudioManager.FLAG_SHOW_UI)
            return ExecResult(true, "Volumen al $l por ciento.")
        }
        return when (cmd) {
            "down" -> {
                am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, 0)
                ExecResult(true, "Volumen bajado.")
            }
            "mute" -> { am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI); ExecResult(true, "") }
            "unmute" -> { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI); ExecResult(true, "Sonido activado.") }
            "max" -> { am.setStreamVolume(s, max, AudioManager.FLAG_SHOW_UI); ExecResult(true, "Volumen al máximo.") }
            "min" -> { am.setStreamVolume(s, 1, AudioManager.FLAG_SHOW_UI); ExecResult(true, "Volumen al mínimo.") }
            else -> {
                am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, 0)
                ExecResult(true, "Volumen subido.")
            }
        }
    }

    private fun battery(): ExecResult {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = if (bm.isCharging) " y está cargando" else ""
        return ExecResult(true, "Le queda un $pct por ciento de batería$charging.", true)
    }

    // ---------- Búsquedas y mapas ----------
    private fun webSearch(q: String): ExecResult {
        if (q.isBlank()) return ExecResult(false, "¿Qué quiere que busque?")
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
        if (!start(i)) start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        return ExecResult(true, "Buscando $q.")
    }

    private fun navigate(dest: String): ExecResult {
        if (dest.isBlank()) return ExecResult(false, "¿A dónde quiere ir?")
        if (!start(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest))))) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))))
        }
        return ExecResult(true, "Calculando la ruta a $dest.")
    }

    private fun mapsSearch(q: String): ExecResult {
        start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
        return ExecResult(true, "Buscando $q cerca de usted.")
    }

    // ---------- Pantalla ----------
    private fun screen(a: Action): ExecResult {
        val svc = JarvisAccessibilityService.instance
            ?: return ExecResult(false, "Para controlar la pantalla necesito que active el servicio de accesibilidad de Janus. Lo tiene en mis ajustes.")
        return when (a.type) {
            "click" -> if (svc.clickText(a.arg("text"))) ExecResult(true, "")
            else ExecResult(false, "No encuentro «${a.arg("text")}» en la pantalla.")
            "scroll" -> { svc.scroll(a.arg("direction").ifBlank { "down" }); ExecResult(true, "") }
            "global" -> if (svc.global(a.arg("cmd"))) ExecResult(true, "") else ExecResult(false, "No puedo hacer eso en este móvil.")
            "type_text" -> if (svc.typeText(a.arg("text"))) ExecResult(true, "Escrito.")
            else ExecResult(false, "No encuentro ningún campo de texto.")
            "read_screen" -> {
                val t = svc.readScreen()
                if (t.isBlank()) ExecResult(false, "No veo texto en la pantalla.") else ExecResult(true, t, true)
            }
            else -> ExecResult(false, "Todavía no sé hacer eso.")
        }
    }
}
