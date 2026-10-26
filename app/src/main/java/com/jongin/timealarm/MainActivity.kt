package com.jongin.timealarm

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private val states = HashMap<Int, TextView>()
    private val WC = LinearLayout.LayoutParams.WRAP_CONTENT

    private val ticker = object : Runnable {
        override fun run() {
            updateStates()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(14), dp(24), dp(14), dp(40))
        sv.addView(root)
        setContentView(sv)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    // ---------- 작은 도우미들 ----------
    private fun label(t: String, size: Float = 14f, top: Int = 10): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setPadding(0, dp(top), 0, dp(4))
        return v
    }

    private fun section(t: String): TextView {
        val v = label(t, 18f, 22)
        v.setTypeface(null, Typeface.BOLD)
        return v
    }

    private fun text(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 14f
        return v
    }

    private fun row(): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        return r
    }

    private fun watcher(f: (String) -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            f(s?.toString() ?: "")
        }
    }

    private fun numField(v: Int, widthDp: Int, hint: String, onChange: (Int) -> Unit): EditText {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setSingleLine()
        e.hint = hint
        e.setText(if (v > 0) v.toString() else "")
        e.layoutParams = LinearLayout.LayoutParams(dp(widthDp), WC)
        e.addTextChangedListener(watcher { onChange(it.trim().toIntOrNull() ?: 0) })
        return e
    }

    private fun seek(title: String, min: Int, max: Int, cur: Int, unit: String, onSet: (Int) -> Unit) {
        val lab = label("$title: $cur$unit", 14f, 8)
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = (cur - min).coerceIn(0, max - min)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + min
                lab.text = "$title: $v$unit"
                if (fromUser) onSet(v)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(lab)
        root.addView(sb)
    }

    private fun edit(i: Int, f: (Slot) -> Unit) {
        val l = Store.load(this)
        f(l[i])
        Store.save(this, l)
        OverlayService.instance?.refreshNow()
    }

    private fun toast(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }

    // ---------- 화면 ----------
    private fun render() {
        root.removeAllViews()
        states.clear()

        root.addView(label("⏰ 시간알림", 24f, 0))

        if (!Settings.canDrawOverlays(this)) {
            val perm = Button(this)
            perm.text = "① '다른 앱 위에 표시' 권한 허용"
            perm.setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
            root.addView(perm)
        }

        val tg = Button(this)
        tg.text = if (OverlayService.running) "슬롯바 끄기" else "슬롯바 켜기"
        tg.setOnClickListener { toggleOverlay() }
        root.addView(tg)

        // 진동 알림
        root.addView(section("📳 진동 알림 시간"))

        val vr = row()
        vr.addView(numField(Store.vibMin(this), 70, "분") {
            Store.p(this@MainActivity).edit().putInt("vibMin", it).apply()
        })
        vr.addView(text("분 전     "))
        vr.addView(numField(Store.vibSec(this), 70, "초") {
            Store.p(this@MainActivity).edit().putInt("vibSec", it).apply()
        })
        vr.addView(text("초 전"))
        root.addView(vr)
        root.addView(
            label(
                "비워 두거나 0이면 그 알림은 꺼져요. 항목마다 주기 오른쪽 '알림'을 체크한 항목만 길게 한 번 울려요. 슬롯바가 켜져 있어야 동작해요.",
                12f, 2
            )
        )

        // 슬롯바 설정
        root.addView(section("🪟 창설정"))
        seek("크기 배율 (글자·버튼)", 30, 100, Store.scale(this), "%") {
            Store.p(this@MainActivity).edit().putInt("scale", it).apply()
            OverlayService.instance?.rebuildAll()
        }
        seek("가로 폭", 80, 420, Store.width(this), "dp") {
            Store.p(this@MainActivity).edit().putInt("width", it).apply()
            OverlayService.instance?.applyStyle()
        }
        seek("투명도 (낮을수록 투명)", 20, 100, Store.alpha(this), "%") {
            Store.p(this@MainActivity).edit().putInt("alpha", it).apply()
            OverlayService.instance?.applyStyle()
        }

        // 슬롯바 이름 강조 시간
        root.addView(label("✨ 이름 강조 시간", 15f, 14))
        val hy = row()
        hy.addView(text("🟨 노란색  "))
        hy.addView(numField(Store.hlMin(this), 70, "분") {
            Store.p(this@MainActivity).edit().putInt("hlMin", it).apply()
        })
        hy.addView(text("분 전부터"))
        root.addView(hy)
        val hr = row()
        hr.addView(text("🟥 빨간 깜빡임  "))
        hr.addView(numField(Store.hlSec(this), 70, "초") {
            Store.p(this@MainActivity).edit().putInt("hlSec", it).apply()
        })
        hr.addView(text("초 전부터"))
        root.addView(hr)
        root.addView(label("비워 두거나 0이면 그 강조는 꺼져요. 슬롯바에 표시된 모든 항목에 적용돼요.", 12f, 2))

        // 항목 목록
        val bh = row()
        val bt = section("📋 목록")
        bt.layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        val rb = Button(this)
        rb.text = "리붓"
        rb.setTextColor(0xFFFFFFFF.toInt())
        rb.setTypeface(null, Typeface.BOLD)
        val rbg = GradientDrawable()
        rbg.setColor(0xFFD32F2F.toInt())
        rbg.cornerRadius = dp(8).toFloat()
        rb.background = rbg
        rb.setPadding(dp(18), dp(8), dp(18), dp(8))
        rb.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("저장된 모든 항목의 타이머를 지금 시작할까요?")
                .setPositiveButton("리붓") { _, _ ->
                    val n = Store.startAll(this@MainActivity)
                    if (n > 0) {
                        buzz(this@MainActivity, 60)
                        toast("${n}개 타이머 시작")
                    } else {
                        toast("이름과 주기가 저장된 항목이 없어요")
                    }
                    updateStates()
                    OverlayService.instance?.refreshNow()
                }
                .setNegativeButton("취소", null)
                .show()
        }
        bh.addView(bt)
        bh.addView(rb)
        root.addView(bh)
        root.addView(
            label(
                "이름과 주기(시간·분·초)를 넣으면 자동 저장돼요. 왼쪽을 체크한 것만 슬롯바에 표시돼요.",
                12f, 2
            )
        )
        // 처음엔 5칸만 보이고, '추가'로 한 칸씩 늘림 (최대 15칸). 이미 입력된 칸은 항상 보임
        val list = Store.load(this)
        val lastUsed = list.indexOfLast { it.name.isNotBlank() || it.secs > 0 }
        // 앱을 다시 열면 입력된 칸까지만 보이고(최소 5칸), 비어 있는 추가 칸은 다시 숨김
        var shown = maxOf(lastUsed + 1, 5).coerceAtMost(SLOTS)
        val slotBox = LinearLayout(this)
        slotBox.orientation = LinearLayout.VERTICAL
        root.addView(slotBox)
        for (i in 0 until shown) addSlotRow(slotBox, i, list[i])

        val add = Button(this)
        add.text = "+ 추가 ($shown/$SLOTS)"
        if (shown >= SLOTS) add.isEnabled = false
        add.setOnClickListener {
            if (shown < SLOTS) {
                addSlotRow(slotBox, shown, Store.load(this@MainActivity)[shown])
                shown++
                add.text = "+ 추가 ($shown/$SLOTS)"
                if (shown >= SLOTS) add.isEnabled = false
            }
        }
        root.addView(add)

        val all = Button(this)
        all.text = "전체 타이머 초기화"
        all.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("모든 항목의 타이머를 초기화할까요?\n(이름과 주기는 그대로 남아요)")
                .setPositiveButton("초기화") { _, _ ->
                    val l = Store.load(this@MainActivity)
                    for (b in l) b.due = 0
                    Store.save(this@MainActivity, l)
                    updateStates()
                    OverlayService.instance?.refreshNow()
                }
                .setNegativeButton("취소", null)
                .show()
        }
        root.addView(all)

        updateStates()
    }

    private fun addSlotRow(parent: LinearLayout, i: Int, b: Slot) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(6), dp(4), dp(6), dp(4))
        val bg = GradientDrawable()
        bg.setStroke(dp(1), 0xFFDDDDDD.toInt())
        bg.cornerRadius = dp(8).toFloat()
        box.background = bg
        val m = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WC)
        m.topMargin = dp(6)
        box.layoutParams = m

        val r1 = row()
        val show = CheckBox(this)
        show.isChecked = b.show
        show.setOnCheckedChangeListener { _, on -> edit(i) { it.show = on } }

        val num = text("${i + 1}.")
        num.setPadding(0, 0, dp(4), 0)

        val name = EditText(this)
        name.hint = "이름"
        name.setSingleLine()
        name.setText(b.name)
        name.layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        name.addTextChangedListener(watcher { s -> edit(i) { it.name = s.trim() } })

        r1.addView(show)
        r1.addView(num)
        r1.addView(name)

        // 주기: 시 / 분 / 초
        val rt = row()
        rt.setPadding(dp(8), 0, 0, 0)
        var upd: () -> Unit = {}
        val hf = numField(b.secs / 3600, 60, "0") { upd() }
        val mf = numField(b.secs % 3600 / 60, 56, "0") { upd() }
        val sf = numField(b.secs % 60, 56, "0") { upd() }
        upd = {
            val h = hf.text.toString().trim().toIntOrNull() ?: 0
            val m = mf.text.toString().trim().toIntOrNull() ?: 0
            val s = sf.text.toString().trim().toIntOrNull() ?: 0
            edit(i) { it.secs = h * 3600 + m * 60 + s }
        }
        rt.addView(hf)
        rt.addView(text("시 "))
        rt.addView(mf)
        rt.addView(text("분 "))
        rt.addView(sf)
        rt.addView(text("초"))

        // 오른쪽 끝: 이 항목 개별 알림
        val sp = TextView(this)
        sp.layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        rt.addView(sp)
        val al = CheckBox(this)
        al.text = "알림"
        al.isChecked = b.alarm
        al.setOnCheckedChangeListener { _, on -> edit(i) { it.alarm = on } }
        rt.addView(al)

        val r2 = row()
        val st = TextView(this)
        st.textSize = 13f
        st.layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        st.setPadding(dp(6), 0, 0, 0)
        states[i] = st

        val k = Button(this)
        k.text = "시작"
        k.setOnClickListener {
            if (Store.startTimer(this@MainActivity, i)) {
                buzz(this@MainActivity, 60)
                val bb = Store.load(this@MainActivity)[i]
                toast("${bb.name} 시작! ${fmtDur(bb.secs)} 후 완료")
            } else {
                toast("이름과 주기를 먼저 입력하세요")
            }
            updateStates()
            OverlayService.instance?.refreshNow()
        }

        val rs = Button(this)
        rs.text = "리셋"
        rs.setOnClickListener {
            Store.reset(this@MainActivity, i)
            updateStates()
            OverlayService.instance?.refreshNow()
        }

        r2.addView(st)
        r2.addView(k)
        r2.addView(rs)

        box.addView(r1)
        box.addView(rt)
        box.addView(r2)
        parent.addView(box)
    }

    private fun updateStates() {
        if (states.isEmpty()) return
        val l = Store.load(this)
        val now = nowSec()
        for ((i, tv) in states) {
            tv.text = stateText(l[i], now)
        }
    }

    private fun toggleOverlay() {
        if (OverlayService.running) {
            stopService(Intent(this, OverlayService::class.java))
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "먼저 '다른 앱 위에 표시' 권한을 허용하세요", Toast.LENGTH_LONG).show()
                return
            }
            val i = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        ui.postDelayed({ render() }, 500)
    }
}
