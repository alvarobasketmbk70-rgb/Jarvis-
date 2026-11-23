package com.jarvis.asistente

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.provider.CalendarContract
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToInt
import kotlin.random.Random

/** Informe matutino al estilo de la película: hora, tiempo, agenda y batería. Se ejecuta fuera del hilo principal. */
object Briefing {

    fun morning(ctx: Context): String {
        val prefs = Prefs(ctx)
        val n = prefs.userName
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val saludo = when (h) {
            in 6..12 -> "Buenos días"
            in 13..20 -> "Buenas tardes"
            else -> "Buenas noches"
        }
        val parts = ArrayList<String>()
        val hora = String.format(ES, "%d:%02d", h, c.get(Calendar.MINUTE))
        val dia = SimpleDateFormat("EEEE d 'de' MMMM", ES).format(Date())
        parts.add("$saludo, $n. ${if (h == 1 || h == 13) "Es la" else "Son las"} $hora del $dia.")
        weather(ctx)?.let { parts.add(it) }
        parts.add(agenda(ctx))
        parts.add(battery(ctx))
        parts.add(
            listOf(
                "¿Por dónde quiere empezar?", "Todo listo para el día, $n.",
                "Le deseo un día productivo. O al menos entretenido.", "Quedo a la espera de sus órdenes."
            )[Random.nextInt(4)]
        )
        return parts.joinToString(" ")
    }

    // ---------- Tiempo (Open-Meteo: gratis, sin clave) ----------
    private fun location(ctx: Context): Location? {
        val ok = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ok) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        return try {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null } }
                .maxByOrNull { it.time }
        } catch (_: Exception) {
            null
        }
    }

    private fun describe(code: Int): String = when (code) {
        0 -> "despejado"
        1 -> "mayormente despejado"
        2 -> "parcialmente nublado"
        3 -> "nublado"
        45, 48 -> "con niebla"
        in 51..57 -> "con llovizna"
        in 61..67 -> "lloviendo"
        in 71..77 -> "nevando"
        in 80..82 -> "con chubascos"
        in 85..86 -> "con chubascos de nieve"
        in 95..99 -> "con tormenta"
        else -> "variable"
    }

    fun weather(ctx: Context): String? {
        val loc = location(ctx) ?: return "No tengo acceso a su ubicación, así que el parte del tiempo tendrá que esperar."
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}" +
                    "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=1"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            conn.disconnect()
            val cur = o.getJSONObject("current")
            val daily = o.getJSONObject("daily")
            val t = cur.getDouble("temperature_2m").roundToInt()
            val desc = describe(cur.getInt("weather_code"))
            val max = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt()
            val min = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt()
            val rain = daily.optJSONArray("precipitation_probability_max")?.optInt(0, 0) ?: 0
            val sb = StringBuilder("Ahora mismo hay $t grados y está $desc; hoy, entre $min y $max grados")
            sb.append(if (rain >= 50) ", con un $rain por ciento de probabilidad de lluvia. Le sugiero el paraguas." else ".")
            sb.toString()
        } catch (e: Exception) {
            "No he podido consultar el tiempo; parece que no hay conexión."
        }
    }

    // ---------- Agenda ----------
    private fun agenda(ctx: Context): String {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "No tengo permiso para ver su agenda."
        }
        val now = System.currentTimeMillis()
        val end = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
        }.timeInMillis
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString()).appendPath(end.toString()).build()
        val items = ArrayList<String>()
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
                null, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                val fmt = SimpleDateFormat("H:mm", ES)
                while (c.moveToNext() && items.size < 5) {
                    val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "un evento sin título"
                    items.add(if (c.getInt(2) == 1) "$title, todo el día" else "a las ${fmt.format(Date(c.getLong(1)))}, $title")
                }
            }
        } catch (_: Exception) {
            return "No he podido leer su agenda."
        }
        return when (items.size) {
            0 -> "No tiene compromisos en la agenda para lo que queda de hoy."
            1 -> "En su agenda: ${items[0]}."
            else -> "Tiene ${items.size} compromisos: " + items.dropLast(1).joinToString("; ") + "; y " + items.last() + "."
        }
    }

    private fun battery(ctx: Context): String {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return when {
            bm.isCharging -> "La batería está al $p por ciento y cargando."
            p <= 20 -> "Por cierto, la batería está al $p por ciento. Le recomendaría acercarse a un enchufe."
            else -> "La batería está al $p por ciento."
        }
    }
}
