package com.jarvis.asistente

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.ContactsContract
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

val ES: Locale = Locale("es", "ES")

/** Preferencias de usuario. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()
    var model: String
        get() = sp.getString("model", "") ?: ""
        set(v) = sp.edit().putString("model", v.trim()).apply()
    var userName: String
        get() = (sp.getString("user_name", "") ?: "").ifBlank { "señor" }
        set(v) = sp.edit().putString("user_name", v.trim()).apply()
    var countryCode: String
        get() = (sp.getString("country_code", "") ?: "").ifBlank { "34" }
        set(v) = sp.edit().putString("country_code", v.filter { it.isDigit() }).apply()
    var voiceName: String
        get() = ""
        set(v) = sp.edit().putString("voice", v).apply()
    var conversationMode: Boolean
        get() = sp.getBoolean("conversation", true)
        set(v) = sp.edit().putBoolean("conversation", v).apply()
    var autoSendWhatsApp: Boolean
        get() = sp.getBoolean("auto_wa", true)
        set(v) = sp.edit().putBoolean("auto_wa", v).apply()
    var neuralVoice: Boolean
        get() = true
        set(v) = sp.edit().putBoolean("neural_voice", v).apply()
    var neuralVoiceName: String
        get() = "Iapetus"
        set(v) = sp.edit().putString("neural_voice_name", v).apply()
    var groqKey: String
        get() = sp.getString("groq_key", "") ?: ""
        set(v) = sp.edit().putString("groq_key", v.trim()).apply()
    var elevenKey: String
        get() = sp.getString("eleven_key", "") ?: ""
        set(v) = sp.edit().putString("eleven_key", v.trim()).apply()
    var aiEffect: Boolean
        get() = true
        set(v) = sp.edit().putBoolean("ai_effect", v).apply()
    var voicePitch: Float
        get() = 0.92f
        set(v) = sp.edit().putFloat("voice_pitch", v).apply()
    var listenScreenOff: Boolean
        get() = sp.getBoolean("listen_screen_off", true)
        set(v) = sp.edit().putBoolean("listen_screen_off", v).apply()
    var wakeWord: Boolean
        get() = sp.getBoolean("wake_word", true)
        set(v) = sp.edit().putBoolean("wake_word", v).apply()
}

object TextUtil {
    /** Quita tildes conservando exactamente la misma longitud (necesario para alinear índices). */
    fun stripAccents(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(
                when (ch) {
                    'á', 'à', 'ä', 'â' -> 'a'
                    'é', 'è', 'ë', 'ê' -> 'e'
                    'í', 'ì', 'ï', 'î' -> 'i'
                    'ó', 'ò', 'ö', 'ô' -> 'o'
                    'ú', 'ù', 'ü', 'û' -> 'u'
                    'ñ' -> 'n'
                    'ç' -> 'c'
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    fun norm(s: String): String =
        stripAccents(s.lowercase(ES))
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun cap(s: String): String =
        s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(ES) else it.toString() }

    fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
    }

    /** Puntuación 0..1 de lo bien que [q] (normalizado) encaja con [target] (normalizado). */
    fun matchScore(q: String, target: String): Double {
        if (q.isEmpty() || target.isEmpty()) return 0.0
        if (q == target) return 1.0
        val tt = target.split(" ")
        val qt = q.split(" ")
        if (target.startsWith("$q ")) return 0.95
        if (qt.all { it in tt }) return 0.9
        if (q.length >= 3 && target.contains(q)) return 0.85
        var best = similarity(q, target)
        for (t in tt) best = max(best, similarity(q, t) * 0.95)
        if (qt.size > 1 && tt.size >= qt.size) {
            best = max(best, similarity(q, tt.take(qt.size).joinToString(" ")) * 0.95)
        }
        return best * 0.9
    }
}

data class Contact(val name: String, val number: String)

object Contacts {
    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun find(ctx: Context, query: String): Contact? {
        val digits = query.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 3 && query.none { it.isLetter() }) return Contact(digits, digits)
        if (!hasPermission(ctx)) return null
        val q = TextUtil.norm(query)
        if (q.isEmpty()) return null
        var best: Contact? = null
        var bestScore = 0.0
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val n = TextUtil.norm(name)
                val score = TextUtil.matchScore(q, n) - abs(n.length - q.length) * 0.001
                if (score > bestScore) {
                    bestScore = score
                    best = Contact(name, num)
                }
            }
        }
        return if (bestScore >= 0.72) best else null
    }

    /** Convierte un número a formato internacional sin "+" (lo que espera WhatsApp). */
    fun toInternational(number: String, cc: String): String {
        val raw = number.filter { it.isDigit() || it == '+' }
        if (raw.startsWith("+")) return raw.filter { it.isDigit() }
        val d = raw.filter { it.isDigit() }
        if (d.startsWith("00")) return d.drop(2)
        if (d.length > 10 && d.startsWith(cc)) return d
        return cc + d.trimStart('0')
    }
}

object Apps {
    fun find(ctx: Context, query: String): Pair<String, Intent>? {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list: List<ResolveInfo> = pm.queryIntentActivities(main, 0)
        val q = TextUtil.norm(query)
        if (q.isEmpty()) return null
        var best: ResolveInfo? = null
        var bestLabel = ""
        var bestScore = 0.0
        for (ri in list) {
            if (ri.activityInfo.packageName == ctx.packageName) continue
            val label = ri.loadLabel(pm).toString()
            val score = TextUtil.matchScore(q, TextUtil.norm(label))
            if (score > bestScore) {
                bestScore = score
                best = ri
                bestLabel = label
            }
        }
        val b = best ?: return null
        if (bestScore < 0.6) return null
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(b.activityInfo.packageName, b.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return Pair(bestLabel, intent)
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
