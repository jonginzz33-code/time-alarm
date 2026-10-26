package com.jongin.bosstimer

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val SLOTS = 15

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

fun nowSec(): Long = System.currentTimeMillis() / 1000

class Boss(
    var name: String = "",
    var secs: Int = 0,          // 젠타임 (초 단위)
    var spawn: Long = 0,
    var show: Boolean = false
)

object Store {
    fun p(c: Context): SharedPreferences = c.getSharedPreferences("boss_local", Context.MODE_PRIVATE)

    fun load(c: Context): MutableList<Boss> {
        val out = MutableList(SLOTS) { Boss() }
        val s = p(c).getString("bosses", null) ?: return out
        try {
            val a = JSONArray(s)
            for (i in 0 until minOf(a.length(), SLOTS)) {
                val o = a.getJSONObject(i)
                // 예전 버전(분 단위) 데이터도 그대로 불러오기
                val secs = if (o.has("secs")) o.optInt("secs") else o.optInt("minutes") * 60
                out[i] = Boss(
                    o.optString("name"),
                    secs,
                    o.optLong("spawn"),
                    o.optBoolean("show")
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun save(c: Context, list: List<Boss>) {
        val a = JSONArray()
        for (b in list) {
            a.put(
                JSONObject()
                    .put("name", b.name)
                    .put("secs", b.secs)
                    .put("spawn", b.spawn)
                    .put("show", b.show)
            )
        }
        p(c).edit().putString("bosses", a.toString()).apply()
    }

    /** 처치: 지금 시각 + 젠타임(초)을 다음 젠 시각으로 저장 */
    fun kill(c: Context, i: Int): Boolean {
        val l = load(c)
        val b = l[i]
        if (b.name.isBlank() || b.secs <= 0) return false
        b.spawn = nowSec() + b.secs.toLong()
        save(c, l)
        return true
    }

    fun reset(c: Context, i: Int) {
        val l = load(c)
        l[i].spawn = 0
        save(c, l)
    }

    fun vib(c: Context): Boolean = p(c).getBoolean("vib", true)
    fun vibMin(c: Context): Int = p(c).getInt("vibMin", 5)
    fun vibSec(c: Context): Int = p(c).getInt("vibSec", 30)
    fun scale(c: Context): Int = p(c).getInt("scale", 50)
    fun width(c: Context): Int = p(c).getInt("width", 150)
    fun alpha(c: Context): Int = p(c).getInt("alpha", 85)
    fun x(c: Context): Int = p(c).getInt("x", 20)
    fun y(c: Context): Int = p(c).getInt("y", 120)
}

fun fmtLeft(r: Long): String {
    val h = r / 3600
    val m = r % 3600 / 60
    val s = r % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

/** 젠타임을 "5시간 33분 30초"처럼 표시 (0인 단위는 생략) */
fun fmtDur(sec: Int): String {
    if (sec <= 0) return "0초"
    val h = sec / 3600
    val m = sec % 3600 / 60
    val s = sec % 60
    val parts = ArrayList<String>()
    if (h > 0) parts.add("${h}시간")
    if (m > 0) parts.add("${m}분")
    if (s > 0) parts.add("${s}초")
    return parts.joinToString(" ")
}

fun fmtClock(ts: Long): String {
    val d = Date(ts * 1000)
    val day = SimpleDateFormat("yyyyMMdd", Locale.KOREA)
    return if (day.format(d) == day.format(Date())) SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(d)
    else SimpleDateFormat("M/d HH:mm:ss", Locale.KOREA).format(d)
}

fun stateText(b: Boss, now: Long): String {
    if (b.spawn <= 0) return "기록 없음"
    val r = b.spawn - now
    return if (r > 0) "젠 ${fmtClock(b.spawn)} · 남음 ${fmtLeft(r)}"
    else "젠 ${fmtClock(b.spawn)} · 젠!"
}

fun vibrate(c: Context, pattern: LongArray) {
    try {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            v.vibrate(pattern, -1)
        }
    } catch (e: Exception) {
    }
}
