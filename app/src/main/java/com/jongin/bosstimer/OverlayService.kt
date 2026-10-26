package com.jongin.bosstimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class OverlayService : Service() {
    companion object {
        @Volatile
        var running = false

        @Volatile
        var instance: OverlayService? = null
    }

    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var wm: WindowManager
    private lateinit var box: LinearLayout
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var status: TextView
    private lateinit var list: LinearLayout

    private var added = false
    private var bosses: List<Boss> = emptyList()
    private var offset = 0L
    private var fetching = false
    private var lastFetch = 0L
    private var shown: List<Int> = emptyList()
    private val cds = HashMap<Int, TextView>()

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            h.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        running = true
        startFg()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            build()
        } catch (e: Exception) {
            Toast.makeText(this, "오버레이를 열 수 없습니다. 권한을 확인하세요", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        instance = this
        h.post(tick)
    }

    override fun onDestroy() {
        running = false
        instance = null
        h.removeCallbacksAndMessages(null)
        try {
            if (added) wm.removeView(box)
        } catch (e: Exception) {
        }
        io.shutdownNow()
        super.onDestroy()
    }

    private fun startFg() {
        val ch = "boss_overlay"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(ch, "보스 타이머", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val nb: Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch) else Notification.Builder(this)
        nb.setContentTitle("보스 타이머 실행 중")
        nb.setContentText("눌러서 설정 열기")
        nb.setSmallIcon(android.R.drawable.ic_menu_recent_history)
        nb.setContentIntent(pi)
        startForeground(1, nb.build())
    }

    private fun chip(label: String, color: Int, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.textSize = 13f
        t.gravity = Gravity.CENTER
        t.setPadding(dp(8), dp(5), dp(8), dp(5))
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadius = dp(6).toFloat()
        t.background = bg
        val m = LinearLayout.LayoutParams(WC, WC)
        m.leftMargin = dp(3)
        t.layoutParams = m
        t.setOnClickListener { onClick() }
        return t
    }

    private fun build() {
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(6), dp(4), dp(6), dp(6))
        val bg = GradientDrawable()
        bg.setColor(0xEE101018.toInt())
        bg.cornerRadius = dp(10).toFloat()
        box.background = bg

        // 머리줄: 이동 / 크기 / 투명도 / 닫기
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        val handle = TextView(this)
        handle.text = "≡ 이동"
        handle.setTextColor(0xFFCCCCCC.toInt())
        handle.textSize = 14f
        handle.setPadding(dp(6), dp(6), dp(6), dp(6))
        handle.layoutParams = LinearLayout.LayoutParams(0, WC, 1f)

        lp = WindowManager.LayoutParams(
            dp(Cfg.width(this)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = Cfg.x(this)
        lp.y = Cfg.y(this)

        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x
                    sy = lp.y
                    tx = e.rawX
                    ty = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - tx).toInt()
                    lp.y = sy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(box, lp)
                }
                MotionEvent.ACTION_UP -> {
                    Cfg.p(this@OverlayService).edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                }
            }
            true
        }

        val gray = 0xFF33384A.toInt()
        head.addView(handle)
        head.addView(chip("◀", gray) { changeWidth(-20) })
        head.addView(chip("▶", gray) { changeWidth(20) })
        head.addView(chip("투−", gray) { changeAlpha(-10) })
        head.addView(chip("투+", gray) { changeAlpha(10) })
        head.addView(chip("✕", 0xFF7A2B2B.toInt()) { stopSelf() })

        status = TextView(this)
        status.setTextColor(0xFFFFB74D.toInt())
        status.textSize = 11f
        status.visibility = View.GONE

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL

        box.addView(head)
        box.addView(status)
        box.addView(list)
        box.alpha = Cfg.alpha(this) / 100f

        wm.addView(box, lp)
        added = true
        rebuild()
    }

    private fun changeWidth(d: Int) {
        val w = (Cfg.width(this) + d).coerceIn(160, 420)
        Cfg.p(this).edit().putInt("width", w).apply()
        applyStyle()
    }

    private fun changeAlpha(d: Int) {
        val a = (Cfg.alpha(this) + d).coerceIn(20, 100)
        Cfg.p(this).edit().putInt("alpha", a).apply()
        applyStyle()
    }

    fun applyStyle() {
        if (!added) return
        lp.width = dp(Cfg.width(this))
        box.alpha = Cfg.alpha(this) / 100f
        wm.updateViewLayout(box, lp)
    }

    fun refreshNow() {
        if (added) rebuild()
    }

    private fun setStatus(t: String) {
        status.text = t
        status.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun wanted(): List<Boss> {
        val sel = Cfg.sel(this)
        return bosses.filter { it.name.isNotBlank() && it.slot in sel }
    }

    private fun rebuild() {
        val want = wanted()
        shown = want.map { it.slot }
        list.removeAllViews()
        cds.clear()
        if (want.isEmpty()) {
            val t = TextView(this)
            t.text = if (bosses.isEmpty()) "불러오는 중..." else "앱에서 표시할 보스를 선택하세요"
            t.setTextColor(0xFFAAAAAA.toInt())
            t.textSize = 12f
            t.setPadding(dp(4), dp(4), dp(4), dp(4))
            list.addView(t)
            return
        }
        for (b in want) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(3), 0, dp(3))

            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            left.layoutParams = LinearLayout.LayoutParams(0, WC, 1f)

            val nm = TextView(this)
            nm.text = b.name
            nm.setTextColor(Color.WHITE)
            nm.textSize = 15f
            nm.setTypeface(null, Typeface.BOLD)
            nm.maxLines = 1
            nm.ellipsize = TextUtils.TruncateAt.END

            val info = TextView(this)
            info.text = if (b.spawn > 0) "${b.spawnText} 젠 · ${b.who}" else "기록 없음"
            info.setTextColor(0xFFAAAAAA.toInt())
            info.textSize = 11f
            info.maxLines = 1
            info.ellipsize = TextUtils.TruncateAt.END

            left.addView(nm)
            left.addView(info)

            val cd = TextView(this)
            cd.textSize = 17f
            cd.setTextColor(Color.WHITE)
            cd.typeface = Typeface.MONOSPACE
            cd.setPadding(dp(6), 0, dp(6), 0)
            cds[b.slot] = cd

            val killBtn = chip("처치", 0xFFC0392B.toInt()) { kill(b) }

            row.addView(left)
            row.addView(cd)
            row.addView(killBtn)
            list.addView(row)
        }
        updateCd()
    }

    private fun fmt(r: Long): String {
        val hh = r / 3600
        val mm = r % 3600 / 60
        val ss = r % 60
        return if (hh > 0) String.format("%d:%02d:%02d", hh, mm, ss) else String.format("%02d:%02d", mm, ss)
    }

    private fun updateCd() {
        val now = System.currentTimeMillis() / 1000 + offset
        for (b in bosses) {
            val tv = cds[b.slot] ?: continue
            if (b.spawn <= 0) {
                tv.text = "--:--"
                tv.setTextColor(Color.GRAY)
                continue
            }
            val r = b.spawn - now
            if (r <= 0) {
                tv.text = "젠!"
                tv.setTextColor(0xFFFF5252.toInt())
            } else {
                tv.text = fmt(r)
                tv.setTextColor(if (r <= 60) 0xFFFFD54F.toInt() else Color.WHITE)
            }
        }
    }

    private fun onTick() {
        val now = System.currentTimeMillis() / 1000
        if (!fetching && now - lastFetch >= 5) fetch()
        if (wanted().map { it.slot } != shown) rebuild() else updateCd()
    }

    private fun fetch() {
        fetching = true
        lastFetch = System.currentTimeMillis() / 1000
        io.execute {
            val r = Api.call(this@OverlayService, "GET", "/api/bosses")
            h.post {
                fetching = false
                if (!running) return@post
                if (r.code == 200) {
                    offset = r.json.optLong("now") - System.currentTimeMillis() / 1000
                    bosses = parseBosses(r.json)
                    setStatus("")
                    rebuild()
                } else if (r.code == 401) {
                    setStatus("로그인이 필요합니다. 앱을 열어 로그인하세요")
                } else {
                    setStatus("서버 연결 실패 (재시도 중)")
                }
            }
        }
    }

    private fun kill(b: Boss) {
        io.execute {
            val r = Api.call(this@OverlayService, "POST", "/api/bosses/${b.slot}/kill")
            h.post {
                if (!running) return@post
                if (r.code == 200) {
                    Toast.makeText(this@OverlayService, "${b.name} 처치 등록", Toast.LENGTH_SHORT).show()
                    if (!fetching) fetch()
                } else {
                    Toast.makeText(
                        this@OverlayService, r.json.optString("error", "등록 실패"), Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
