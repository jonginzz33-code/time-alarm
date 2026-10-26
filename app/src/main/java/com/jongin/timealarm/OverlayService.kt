package com.jongin.timealarm

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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class OverlayService : Service() {
    companion object {
        @Volatile
        var running = false

        @Volatile
        var instance: OverlayService? = null
    }

    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private val h = Handler(Looper.getMainLooper())

    private lateinit var wm: WindowManager
    private lateinit var box: LinearLayout
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var list: LinearLayout

    private var added = false
    private var sig = ""
    private var s = 0.5f
    private val cds = HashMap<Int, TextView>()
    private val names = HashMap<Int, TextView>()
    private val rows = HashMap<Int, LinearLayout>()
    private val fired = HashSet<String>()

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            h.postDelayed(this, 1000)
        }
    }

    /** 크기 배율을 적용한 dp → px */
    private fun px(v: Int): Int = dp(v * s)

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
            Toast.makeText(this, "슬롯바를 열 수 없습니다. 권한을 확인하세요", Toast.LENGTH_LONG).show()
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
        added = false
        super.onDestroy()
    }

    private fun startFg() {
        val ch = "timer_overlay"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(ch, "시간알림", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val nb: Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch) else Notification.Builder(this)
        nb.setContentTitle("시간알림 실행 중")
        nb.setContentText("눌러서 설정 열기")
        nb.setSmallIcon(android.R.drawable.ic_menu_recent_history)
        nb.setContentIntent(pi)
        startForeground(1, nb.build())
    }

    private fun chip(label: String, color: Int, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.textSize = 13f * s
        t.gravity = Gravity.CENTER
        t.setPadding(px(8), px(5), px(8), px(5))
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadius = px(6).toFloat()
        t.background = bg
        val m = LinearLayout.LayoutParams(WC, WC)
        m.leftMargin = px(3)
        t.layoutParams = m
        t.setOnClickListener { onClick() }
        return t
    }

    private fun build() {
        s = Store.scale(this) / 100f
        sig = ""

        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(px(6), px(4), px(6), px(6))
        val bg = GradientDrawable()
        bg.setColor(0xEE101018.toInt())
        bg.cornerRadius = px(10).toFloat()
        box.background = bg

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        val handle = TextView(this)
        handle.text = "≡"
        handle.setTextColor(0xFFCCCCCC.toInt())
        handle.textSize = 16f * s
        handle.setPadding(px(6), px(4), px(6), px(4))
        handle.layoutParams = LinearLayout.LayoutParams(0, WC, 1f)

        lp = WindowManager.LayoutParams(
            dp(Store.width(this)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = Store.x(this)
        lp.y = Store.y(this)

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
                    Store.p(this@OverlayService).edit().putInt("x", lp.x).putInt("y", lp.y).apply()
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

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL

        box.addView(head)
        box.addView(list)
        box.alpha = Store.alpha(this) / 100f

        wm.addView(box, lp)
        added = true
        onTick()
    }

    /** 크기 배율이 바뀌면 창을 새로 그림 */
    fun rebuildAll() {
        if (!added) return
        try {
            wm.removeView(box)
        } catch (e: Exception) {
        }
        added = false
        try {
            build()
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun changeWidth(d: Int) {
        val w = (Store.width(this) + d).coerceIn(80, 420)
        Store.p(this).edit().putInt("width", w).apply()
        applyStyle()
    }

    private fun changeAlpha(d: Int) {
        val a = (Store.alpha(this) + d).coerceIn(20, 100)
        Store.p(this).edit().putInt("alpha", a).apply()
        applyStyle()
    }

    fun applyStyle() {
        if (!added) return
        lp.width = dp(Store.width(this))
        box.alpha = Store.alpha(this) / 100f
        wm.updateViewLayout(box, lp)
    }

    fun refreshNow() {
        if (added) onTick()
    }

    private fun toast(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }

    private fun fill(l: List<Slot>) {
        list.removeAllViews()
        cds.clear()
        names.clear()
        rows.clear()
        val want = ordered(l)
        if (want.isEmpty()) {
            val t = TextView(this)
            t.text = "앱에서 항목을 체크하세요"
            t.setTextColor(0xFFAAAAAA.toInt())
            t.textSize = 12f * s
            t.setPadding(px(4), px(4), px(4), px(4))
            list.addView(t)
            return
        }
        for ((i, b) in want) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, px(3), 0, px(3))

            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            left.layoutParams = LinearLayout.LayoutParams(0, WC, 1f)

            val nm = TextView(this)
            nm.text = b.name
            nm.setTextColor(Color.WHITE)
            nm.textSize = 15f * s
            nm.setTypeface(null, Typeface.BOLD)
            nm.maxLines = 1
            nm.ellipsize = TextUtils.TruncateAt.END

            val info = TextView(this)
            info.text = if (b.due > 0) "${fmtClock(b.due)} 완료" else fmtDur(b.secs)
            info.setTextColor(0xFFAAAAAA.toInt())
            info.textSize = 11f * s
            info.maxLines = 1
            info.ellipsize = TextUtils.TruncateAt.END

            left.addView(nm)
            left.addView(info)

            val cd = TextView(this)
            cd.textSize = 17f * s
            cd.setTextColor(Color.WHITE)
            cd.typeface = Typeface.MONOSPACE
            cd.setPadding(px(4), 0, px(4), 0)
            cds[i] = cd
            names[i] = nm
            rows[i] = row

            val startBtn = chip("시작", 0xFFC0392B.toInt()) { startTimer(i) }
            startBtn.setOnLongClickListener {
                Store.reset(this@OverlayService, i)
                buzz(this@OverlayService, 40)
                onTick()
                true
            }

            row.addView(left)
            row.addView(cd)
            row.addView(startBtn)
            list.addView(row)
        }
    }

    /**
     * 슬롯바 표시 순서: ① 완료된 것(✅)  ② 남은 시간이 적은 순  ③ 기록 없음
     */
    private fun ordered(l: List<Slot>): List<IndexedValue<Slot>> {
        val now = nowSec()
        fun rank(b: Slot): Int = when {
            b.due <= 0 -> 2
            b.due <= now -> 0
            else -> 1
        }
        val shown = l.withIndex().filter { it.value.show && it.value.name.isNotBlank() }
        if (!Store.sortByTime(this)) return shown   // 정렬 끔: 목록 번호 순서
        return shown
            .sortedWith(compareBy<IndexedValue<Slot>>({ rank(it.value) }, { it.value.due }, { it.index }))
    }

    private fun startTimer(i: Int) {
        if (Store.startTimer(this, i)) {
            buzz(this, 60)
        } else {
            toast("주기가 없습니다. 앱에서 입력하세요")
        }
        onTick()
    }

    private fun onTick() {
        if (!added) return
        val l = Store.load(this)
        // 순서가 바뀌면(시작, 완료 등) 슬롯바를 다시 그림
        val order = ordered(l).joinToString(",") { it.index.toString() }
        val sg = l.joinToString("|") { "${it.name},${it.secs},${it.due},${it.show}" } + "#" + order
        if (sg != sig) {
            sig = sg
            fill(l)
        }
        updateCd(l)
        checkAlerts(l)
    }

    private fun updateCd(l: List<Slot>) {
        val now = nowSec()
        val vm = Store.hlMin(this) * 60L       // 노란 강조 시작
        val vs = Store.hlSec(this).toLong()    // 빨간 깜빡임 시작
        for ((i, tv) in cds) {
            val b = l[i]
            val nm = names[i]
            val row = rows[i]
            var level = 0   // 0: 보통, 1: 분 전 구간(노랑), 2: 초 전 구간(빨강 깜빡임)
            if (b.due <= 0) {
                tv.text = "--:--"
                tv.setTextColor(Color.GRAY)
            } else {
                val r = b.due - now
                if (r <= 0) {
                    tv.text = "✅"
                    tv.setTextColor(0xFF4CAF50.toInt())
                } else {
                    tv.text = fmtLeft(r)
                    level = when {
                        vs > 0 && r <= vs -> 2
                        vm > 0 && r <= vm -> 1
                        else -> 0
                    }
                    tv.setTextColor(
                        when (level) {
                            2 -> Color.WHITE              // 빨간 배경 위에서는 흰 글씨가 잘 보임
                            1 -> 0xFFFFD54F.toInt()
                            else -> Color.WHITE
                        }
                    )
                }
            }
            // 슬롯바에서 이름 강조 (알림 팝업 대신)
            if (nm != null && row != null) {
                when (level) {
                    2 -> {
                        nm.setTextColor(Color.WHITE)
                        val on = now % 2 == 0L
                        row.setBackgroundColor(if (on) 0xDDE53935.toInt() else 0x55E53935)
                    }
                    1 -> {
                        nm.setTextColor(0xFFFFD54F.toInt())
                        row.setBackgroundColor(0x33FFD54F)
                    }
                    else -> {
                        nm.setTextColor(Color.WHITE)
                        row.setBackgroundColor(Color.TRANSPARENT)
                    }
                }
            }
        }
    }

    /** '알림' 체크한 항목만, 설정한 분 전 / 초 전에 길게 한 번 진동 */
    private fun checkAlerts(l: List<Slot>) {
        val now = nowSec()
        val vm = Store.vibMin(this)
        val vs = Store.vibSec(this)
        for ((i, b) in l.withIndex()) {
            if (!b.alarm || b.name.isBlank() || b.due <= 0) continue
            val r = b.due - now
            if (vm > 0) fire(i, b, "m", r, vm * 60L)
            if (vs > 0) fire(i, b, "s", r, vs.toLong())
        }
    }

    /** 진동만 울림. 알림 팝업은 띄우지 않고 슬롯바에서 이름을 강조함 */
    private fun fire(i: Int, b: Slot, tag: String, r: Long, thr: Long) {
        // 기준 시각을 막 지난 5초 안에서만 울림 (앱을 늦게 켰을 때 지난 알림이 한꺼번에 울리지 않게)
        if (r <= thr && r > thr - 5) {
            val key = "$i:${b.due}:$tag"
            if (fired.add(key)) {
                buzz(this, 1500)
            }
        }
    }
}
