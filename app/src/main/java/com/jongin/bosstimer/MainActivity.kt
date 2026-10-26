package com.jongin.bosstimer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(28), dp(16), dp(40))
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
    }

    private fun label(t: String, size: Float = 14f): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setPadding(0, dp(12), 0, dp(4))
        return v
    }

    private fun render() {
        root.removeAllViews()
        if (Cfg.token(this).isEmpty()) loginView() else mainView()
    }

    // ---------- 로그인 화면 ----------
    private fun loginView() {
        root.addView(label("⚔ 보스 타이머", 24f))

        val url = EditText(this)
        url.hint = "서버 주소"
        url.setSingleLine()
        url.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        url.setText(Cfg.url(this))

        val id = EditText(this)
        id.hint = "아이디"
        id.setSingleLine()
        id.setText(Cfg.p(this).getString("lastid", "") ?: "")

        val pw = EditText(this)
        pw.hint = "비밀번호"
        pw.setSingleLine()
        pw.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        val msg = TextView(this)
        msg.setTextColor(0xFFC62828.toInt())

        val btn = Button(this)
        btn.text = "로그인"

        root.addView(label("서버 주소 (터널 주소가 바뀌면 여기를 고치세요)"))
        root.addView(url)
        root.addView(label("아이디"))
        root.addView(id)
        root.addView(label("비밀번호"))
        root.addView(pw)
        root.addView(btn)
        root.addView(msg)

        btn.setOnClickListener {
            val u = Api.normalize(url.text.toString())
            val uid = id.text.toString().trim()
            val upw = pw.text.toString()
            Cfg.p(this@MainActivity).edit().putString("url", u).putString("lastid", uid).apply()
            btn.isEnabled = false
            msg.text = "로그인 중..."
            Thread {
                val r = Api.call(
                    this@MainActivity, "POST", "/api/login",
                    JSONObject().put("id", uid).put("pw", upw), token = ""
                )
                ui.post {
                    btn.isEnabled = true
                    if (r.code == 200) {
                        Cfg.p(this@MainActivity).edit()
                            .putString("token", r.json.optString("token"))
                            .putString("me", r.json.optString("id"))
                            .putBoolean("admin", r.json.optBoolean("admin"))
                            .apply()
                        render()
                    } else {
                        msg.text = r.json.optString("error", "오류 (${r.code})")
                    }
                }
            }.start()
        }
    }

    // ---------- 메인 화면 ----------
    private fun mainView() {
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val who = TextView(this)
        who.text = "⚔ ${Cfg.me(this)}님" + (if (Cfg.admin(this)) " (관리자)" else "")
        who.textSize = 18f
        who.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val out = Button(this)
        out.text = "로그아웃"
        out.setOnClickListener { logout() }
        top.addView(who)
        top.addView(out)
        root.addView(top)

        if (!Settings.canDrawOverlays(this)) {
            val perm = Button(this)
            perm.text = "① '다른 앱 위에 표시' 권한 허용"
            perm.setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            root.addView(perm)
        }

        val tg = Button(this)
        tg.text = if (OverlayService.running) "오버레이 끄기" else "오버레이 켜기 (게임 위에 표시)"
        tg.setOnClickListener { toggleOverlay() }
        root.addView(tg)

        seek("투명도 (낮을수록 투명)", 20, 100, Cfg.alpha(this), "%") {
            Cfg.p(this@MainActivity).edit().putInt("alpha", it).apply()
            OverlayService.instance?.applyStyle()
        }
        seek("가로 크기", 160, 420, Cfg.width(this), "dp") {
            Cfg.p(this@MainActivity).edit().putInt("width", it).apply()
            OverlayService.instance?.applyStyle()
        }

        root.addView(label("표시할 보스 선택", 16f))
        val hint = TextView(this)
        hint.text = "불러오는 중..."
        root.addView(hint)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        root.addView(box)
        val rf = Button(this)
        rf.text = "목록 새로고침"
        rf.setOnClickListener { loadBosses(hint, box) }
        root.addView(rf)
        loadBosses(hint, box)
    }

    private fun seek(title: String, min: Int, max: Int, cur: Int, unit: String, onSet: (Int) -> Unit) {
        val lab = label("$title: $cur$unit")
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

    private fun logout() {
        stopService(Intent(this, OverlayService::class.java))
        val t = Cfg.token(this)
        Cfg.p(this).edit().putString("token", "").apply()
        Thread { Api.call(this@MainActivity, "POST", "/api/logout", token = t) }.start()
        render()
    }

    private fun loadBosses(hint: TextView, box: LinearLayout) {
        hint.text = "불러오는 중..."
        Thread {
            val r = Api.call(this@MainActivity, "GET", "/api/bosses")
            ui.post {
                if (r.code == 401) {
                    Cfg.p(this@MainActivity).edit().putString("token", "").apply()
                    render()
                } else if (r.code != 200) {
                    hint.text = r.json.optString("error", "서버 연결 실패") +
                        " (서버 주소와 터널이 켜져 있는지 확인하세요)"
                } else {
                    box.removeAllViews()
                    val list = parseBosses(r.json).filter { it.name.isNotBlank() }
                    hint.text = if (list.isEmpty()) {
                        "등록된 보스가 없습니다. 관리자가 웹에서 보스를 등록하세요."
                    } else {
                        "체크한 보스만 게임 위에 표시됩니다."
                    }
                    val sel = Cfg.sel(this@MainActivity)
                    for (b in list) {
                        val cb = CheckBox(this@MainActivity)
                        cb.text = "${b.slot}. ${b.name}  (${b.minutes}분)"
                        cb.textSize = 16f
                        cb.isChecked = b.slot in sel
                        cb.setOnCheckedChangeListener { _, on ->
                            val s = Cfg.sel(this@MainActivity).toMutableSet()
                            if (on) s.add(b.slot) else s.remove(b.slot)
                            Cfg.setSel(this@MainActivity, s)
                            OverlayService.instance?.refreshNow()
                        }
                        box.addView(cb)
                    }
                }
            }
        }.start()
    }
}
