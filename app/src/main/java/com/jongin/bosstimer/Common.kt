package com.jongin.bosstimer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

const val DEFAULT_URL = "https://ba2f092bbe1ca8.lhr.life"

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

object Cfg {
    fun p(c: Context): SharedPreferences = c.getSharedPreferences("boss", Context.MODE_PRIVATE)
    fun url(c: Context): String = p(c).getString("url", DEFAULT_URL) ?: DEFAULT_URL
    fun token(c: Context): String = p(c).getString("token", "") ?: ""
    fun me(c: Context): String = p(c).getString("me", "") ?: ""
    fun admin(c: Context): Boolean = p(c).getBoolean("admin", false)
    fun alpha(c: Context): Int = p(c).getInt("alpha", 85)
    fun width(c: Context): Int = p(c).getInt("width", 260)
    fun x(c: Context): Int = p(c).getInt("x", 40)
    fun y(c: Context): Int = p(c).getInt("y", 160)

    fun sel(c: Context): Set<Int> =
        (p(c).getString("sel", "") ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

    fun setSel(c: Context, s: Set<Int>) {
        p(c).edit().putString("sel", s.sorted().joinToString(",")).apply()
    }
}

class Boss(
    val slot: Int,
    val name: String,
    val minutes: Int,
    val spawn: Long,
    val spawnText: String,
    val who: String
)

fun parseBosses(j: JSONObject): List<Boss> {
    val a = j.optJSONArray("bosses") ?: return emptyList()
    val out = ArrayList<Boss>()
    for (i in 0 until a.length()) {
        val o = a.getJSONObject(i)
        out.add(
            Boss(
                o.optInt("slot"),
                o.optString("name"),
                o.optInt("minutes"),
                o.optLong("spawn"),
                o.optString("spawn_text"),
                o.optString("who")
            )
        )
    }
    return out
}

object Api {
    class Res(val code: Int, val json: JSONObject)

    fun normalize(u: String): String {
        var s = u.trim().trimEnd('/')
        if (s.isEmpty()) return DEFAULT_URL
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        return s
    }

    fun call(
        c: Context,
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null
    ): Res {
        return try {
            val conn = URL(Cfg.url(c) + path).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "BossTimer/1.0")
            val t = token ?: Cfg.token(c)
            if (t.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $t")
            if (method == "POST") {
                conn.doOutput = true
                val data = (body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { it.write(data) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject()
            }
            Res(code, json)
        } catch (e: Exception) {
            Res(-1, JSONObject().put("error", "연결 실패"))
        }
    }
}
