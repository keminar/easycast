package com.easycast.tv

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Player
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen view onto the player owned by [DlnaService]. Cast from Douyin's live room
 * ("投屏" button) to this TV; the service receives the stream URL and starts this screen.
 *
 * Idle layout, top to bottom: header (title + device/network/IP), log panel, button row.
 * While something plays only the video is shown.
 */
class MainActivity : Activity(), DlnaService.Ui {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView          // centered text while loading / paused
    private lateinit var idleLayout: LinearLayout   // header + log + buttons
    private lateinit var infoView: TextView
    private lateinit var noticeView: TextView
    private lateinit var logView: TextView
    private lateinit var castView: ScrollView
    private lateinit var castList: LinearLayout
    private var castsRendered: List<DlnaService.Cast>? = null
    private lateinit var buttonBar: LinearLayout
    private val buttons = mutableListOf<Button>()

    private var aspect = 0f
    private var currentStatus: String? = null
    private var attached: DlnaService? = null
    private var logEnabled = true
    private var logRenderPending = false
    private val wifi by lazy { WifiHelper(this) }

    private companion object {
        const val REQ_LOCATION = 1
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(status, FrameLayout.LayoutParams(-1, -1))
        root.addView(buildIdleLayout(), FrameLayout.LayoutParams(-1, -1))
        root.addView(buildSeekPanel(), FrameLayout.LayoutParams(dp(720), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(48)
        })
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSurface() }
        setContentView(root)

        // Make sure the background service is up (it normally already is).
        startForegroundService(Intent(this, DlnaService::class.java))
    }

    private fun buildIdleLayout(): LinearLayout {
        idleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(dp(40), dp(24), dp(40), dp(20))
            visibility = View.GONE
        }
        fun text(size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        idleLayout.addView(text(24f, "#FFFFFFFF", bold = true).apply { this.text = "简易投屏 接收端" })
        infoView = text(15f, "#E6FFFFFF")
        idleLayout.addView(infoView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        noticeView = text(15f, "#FFFFD166").apply { visibility = View.GONE }
        idleLayout.addView(noticeView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        idleLayout.addView(
            text(13f, "#99FFFFFF").apply { this.text = "在抖音 / B 站的直播间点「投屏」，选择本设备" },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
        )

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.parseColor("#CCFFFFFF"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            gravity = Gravity.BOTTOM
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#26FFFFFF"))
            }
            // The number of visible lines and the clip width depend on the panel size.
            addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                if (r - l != oldR - oldL || b - t != oldB - oldT) scheduleLogRender()
            }
        }
        // With the log hidden, the same area lists the most recent cast URLs instead.
        castList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        castView = ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#26FFFFFF"))
            }
            addView(castList, FrameLayout.LayoutParams(-1, -2))
            visibility = View.GONE
        }
        val middle = FrameLayout(this)
        middle.addView(logView, FrameLayout.LayoutParams(-1, -1))
        middle.addView(castView, FrameLayout.LayoutParams(-1, -1))
        // Weight 1: takes whatever is left between the header and the buttons, so they stay at the bottom.
        idleLayout.addView(middle, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(12)
            bottomMargin = dp(8)
        })
        // Centered when it fits; scrolls sideways (following focus) if the TV is too narrow for all buttons.
        val scroller = HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            addView(buildButtonBar(), FrameLayout.LayoutParams(-2, -2))
        }
        idleLayout.addView(scroller, LinearLayout.LayoutParams(-1, -2))
        return idleLayout
    }

    override fun onStart() {
        super.onStart()
        AppLog.listener = { scheduleLogRender() }
        attach()
    }

    override fun onStop() {
        AppLog.listener = null
        main.removeCallbacks(attachRetry)
        attached?.let {
            it.ui = null
            it.player.clearVideoSurfaceView(surface)
        }
        attached = null
        super.onStop()
    }

    private val attachRetry = Runnable { attach() }

    /** The service starts asynchronously, so poll briefly until it is ready. */
    private fun attach() {
        val svc = DlnaService.instance
        if (svc == null) {
            main.postDelayed(attachRetry, 100)
            return
        }
        attached = svc
        svc.ui = this
        svc.player.setVideoSurfaceView(surface)
        aspect = svc.aspect
        onStatus(svc.status)
        fitSurface()
    }

    // ---- DlnaService.Ui (main thread) ----

    override fun onStatus(text: String?) {
        currentStatus = text
        refreshUi()
    }

    override fun onAspect(aspect: Float) {
        this.aspect = aspect
        fitSurface()
    }

    /** Idle: header + log + buttons. Busy: centered status text. Playing: video only. */
    private fun refreshUi() {
        val svc = attached
        val idle = svc != null && svc.player.playbackState == Player.STATE_IDLE
        val text = currentStatus
        val notice = text?.takeIf { it != DlnaService.IDLE_STATUS }

        idleLayout.visibility = if (idle) View.VISIBLE else View.GONE
        if (!idle) {
            status.text = notice
            status.visibility = if (notice != null) View.VISIBLE else View.GONE
            return
        }
        status.visibility = View.GONE

        val ip = if (svc!!.localIp == "0.0.0.0") "未获取" else svc.localIp
        infoView.text = "设备名：${svc.deviceName}        网络：${svc.networkLabel()}        IP：$ip" +
            if (svc.localIp == "0.0.0.0") "" else
                "\n电脑浏览器推送：http://${svc.localIp}:${DlnaRenderer.HTTP_PORT}        口令：${svc.webPin}（不会自动更换，可在主界面点「更换口令」手动更换）"
        noticeView.text = notice
        noticeView.visibility = if (notice != null) View.VISIBLE else View.GONE

        logView.visibility = if (logEnabled) View.VISIBLE else View.GONE
        castView.visibility = if (logEnabled) View.GONE else View.VISIBLE
        if (logEnabled) renderLog() else renderCasts(svc)
        // Start on the first button, but never steal focus from a cast-list entry the user is on.
        if (idleLayout.findFocus() == null) buttons.firstOrNull()?.requestFocus()
    }

    // ---- button row (left/right to move, OK to press) ----

    private fun buildButtonBar(): LinearLayout {
        buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // The focused button is scaled up; let it grow past the bar instead of being clipped.
            clipChildren = false
            clipToPadding = false
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val actions = listOf<Pair<String, () -> Unit>>(
            "修改设备名称" to { attached?.let { showRenameDialog(it) } },
            "重新连接 Wi-Fi" to {
                AppLog.i("手动重新连接 Wi-Fi")
                wifi.reconnect()
                toast("正在重新连接 Wi-Fi…")
            },
            "扫描连接 Wi-Fi" to { startWifiScan() },
            "系统 Wi-Fi 设置" to {
                if (!wifi.openSystemSettings(this)) {
                    AppLog.e("无法打开系统设置")
                    toast("无法打开系统设置")
                }
            },
            "更换口令" to { attached?.let { confirmNewPin(it) } },
            // Keep this one last: its click handler finds itself with buttons.last().
            "隐藏日志" to {
                logEnabled = !logEnabled
                buttons.last().text = if (logEnabled) "隐藏日志" else "显示日志"
                refreshUi()
            },
        )
        for ((label, action) in actions) {
            val b = Button(this).apply {
                text = label
                textSize = 17f
                setTextColor(Color.WHITE)
                isAllCaps = false
                isFocusable = true
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener { action() }
                applyButtonStyle(this, false)
                setOnFocusChangeListener { v, focused -> applyButtonStyle(v as Button, focused) }
            }
            buttons += b
            buttonBar.addView(b, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
        return buttonBar
    }

    /** Ask first: every browser tab and the PC program that saved the old PIN stops working. */
    private fun confirmNewPin(svc: DlnaService) {
        AlertDialog.Builder(this)
            .setTitle("更换口令")
            .setMessage("换成新的随机 4 位口令。电脑端程序和浏览器里保存的旧口令会失效，需要重新填写。")
            .setPositiveButton("更换") { _, _ ->
                svc.regeneratePin()
                toast("新口令：${svc.webPin}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyButtonStyle(b: Button, focused: Boolean) {
        // Focus is shown by colour and an outline rather than by scaling, which leaves redraw artefacts.
        b.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (focused) Color.parseColor("#FF1E88E5") else Color.parseColor("#55FFFFFF"))
            setStroke(dp(3), if (focused) Color.WHITE else Color.TRANSPARENT)
        }
    }

    // ---- on-screen log ----

    /** Coalesce bursts of log lines into one redraw. Safe to call from any thread. */
    private fun scheduleLogRender() {
        main.post {
            if (logRenderPending) return@post
            logRenderPending = true
            main.postDelayed({
                logRenderPending = false
                if (logView.visibility == View.VISIBLE) renderLog()
            }, 150)
        }
    }

    private val castTime = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /**
     * The newest cast URLs, shown in full (they wrap). Each entry is focusable: up/down to pick one,
     * OK to play it again. Only rebuilt when the list changed, so focus is not lost on every refresh.
     */
    private fun renderCasts(svc: DlnaService) {
        val casts = svc.recentCasts()
        if (casts == castsRendered) return
        castsRendered = casts

        castList.removeAllViews()
        fun label(text: String, size: Float, color: String) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
        }
        castList.addView(
            label("最近投屏地址（上下键选择，确认键重新播放；最多 5 条，最新在上）", 14f, "#FFFFFFFF"),
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(2), dp(4), dp(8)) },
        )
        if (casts.isEmpty()) {
            castList.addView(
                label("还没有投屏记录。在抖音 / B 站的直播间点「投屏」，选择本设备后，这里会显示收到的地址。", 14f, "#CCFFFFFF"),
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), 0, dp(4), 0) },
            )
            return
        }
        for (c in casts) {
            val item = TextView(this).apply {
                val sb = SpannableStringBuilder()
                sb.append(castTime.format(Date(c.time)))
                sb.setSpan(ForegroundColorSpan(Color.parseColor("#FFB3D4FC")), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("\n").append(c.url.take(400))
                text = sb
                textSize = 14f
                setTextColor(Color.parseColor("#E6FFFFFF"))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isFocusable = true
                isClickable = true
                applyItemStyle(this, false)
                setOnFocusChangeListener { v, focused -> applyItemStyle(v as TextView, focused) }
                setOnClickListener {
                    val s = attached ?: return@setOnClickListener
                    toast("正在重新播放…")
                    s.replay(c.url)
                }
            }
            castList.addView(item, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) })
        }
    }

    private fun applyItemStyle(v: TextView, focused: Boolean) {
        v.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (focused) Color.parseColor("#FF1E88E5") else Color.parseColor("#22FFFFFF"))
            setStroke(dp(2), if (focused) Color.WHITE else Color.TRANSPARENT)
        }
    }

    /** Clip to one panel line; CJK glyphs count as two columns. */
    private fun clip(s: String, maxUnits: Int): String {
        var units = 0
        for ((i, c) in s.withIndex()) {
            units += if (c.code > 0x2E80) 2 else 1
            if (units > maxUnits) return s.substring(0, i) + "…"
        }
        return s
    }

    private fun renderLog() {
        // Show exactly as many of the newest lines as fit, each on a single line.
        val innerH = logView.height - logView.paddingTop - logView.paddingBottom
        val lines = if (innerH > 0) (innerH / logView.lineHeight.coerceAtLeast(1)).coerceIn(3, 60) else 10
        val innerW = logView.width - logView.paddingLeft - logView.paddingRight
        val charW = logView.paint.measureText("0")
        val units = if (innerW > 0 && charW > 0) (innerW / charW).toInt() - 1 else 60

        val sb = SpannableStringBuilder()
        for ((level, line) in AppLog.tail(lines)) {
            val start = sb.length
            sb.append(clip(line, units)).append('\n')
            val color = when (level) {
                'E' -> Color.parseColor("#FFFF6B6B")
                'W' -> Color.parseColor("#FFFFD166")
                else -> null
            }
            if (color != null) sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (sb.isNotEmpty()) sb.delete(sb.length - 1, sb.length)
        logView.text = sb
    }

    /** Letterbox the video: keep its aspect ratio, centered, black bars elsewhere. */
    private fun fitSurface() {
        val rw = root.width
        val rh = root.height
        if (rw == 0 || rh == 0 || aspect <= 0f) return
        val (w, h) = if (rw.toFloat() / rh > aspect) {
            (rh * aspect).toInt() to rh
        } else {
            rw to (rw / aspect).toInt()
        }
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            surface.layoutParams = lp
        }
    }

    // ---- dialogs and Wi-Fi actions ----

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun showRenameDialog(svc: DlnaService) {
        val input = EditText(this).apply {
            setText(svc.deviceName)
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(DlnaService.MAX_NAME_LENGTH))
            setSelectAllOnFocus(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("修改设备名称")
            .setMessage("手机投屏列表里显示的名字\n输入后按键盘上的「确认/完成」键即可保存")
            .setView(input)
            .setPositiveButton("保存") { _, _ -> svc.renameDevice(input.text.toString()) }
            .setNegativeButton("取消", null)
            .create()
        // The on-screen keyboard's confirm key should save directly instead of only closing the keyboard.
        input.setOnEditorActionListener { _, actionId, event ->
            val confirm = actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_NEXT ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (confirm) {
                svc.renameDevice(input.text.toString())
                dialog.dismiss()
            }
            confirm
        }
        dialog.show()
    }

    private fun startWifiScan() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Android needs the location permission before it will return scan results.
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        AppLog.i("开始扫描 Wi-Fi…")
        toast("正在扫描 Wi-Fi…")
        wifi.scan { list ->
            AppLog.i("扫描完成，发现 ${list.size} 个热点，其中 ${list.count { it.saved }} 个已保存")
            if (!isFinishing && !isDestroyed) showHotspots(list)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startWifiScan()
        } else {
            AppLog.w("未授予位置权限，无法扫描 Wi-Fi")
            toast("未授予位置权限，无法扫描。可改用「打开系统 Wi-Fi 设置」")
        }
    }

    private fun showHotspots(list: List<WifiHelper.Hotspot>) {
        if (list.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("没有扫描到 Wi-Fi")
                .setMessage("请确认已授予位置权限、电视的位置信息服务已开启，或改用「打开系统 Wi-Fi 设置」。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val labels = list.map {
            val tag = when {
                it.saved -> "  已保存"
                it.secured -> "  需要密码"
                else -> "  开放"
            }
            "${it.ssid}    信号 ${it.level}/4$tag"
        }
        AlertDialog.Builder(this)
            .setTitle("选择要连接的 Wi-Fi")
            .setItems(labels.toTypedArray()) { _, which ->
                val spot = list[which]
                when {
                    spot.unsupported -> toast("暂不支持企业级（802.1X）网络，请用「打开系统 Wi-Fi 设置」")
                    spot.saved -> showSavedChoice(spot)
                    spot.secured -> showPasswordDialog(spot)
                    else -> connectTo(spot, "")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** A saved network: join with the stored password, or enter a new one if the password changed. */
    private fun showSavedChoice(spot: WifiHelper.Hotspot) {
        AlertDialog.Builder(this)
            .setTitle("${spot.ssid}（已保存）")
            .setMessage("电视以前连过这个网络，可以直接使用已保存的密码连接。")
            .setPositiveButton("直接连接") { _, _ -> connectTo(spot, "") }
            .setNeutralButton("输入新密码") { _, _ -> showPasswordDialog(spot) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPasswordDialog(spot: WifiHelper.Hotspot) {
        val input = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("连接 ${spot.ssid}")
            .setMessage("输入 Wi-Fi 密码；之前连过这个网络的话可以留空直接连接")
            .setView(input)
            .setPositiveButton("连接") { _, _ -> connectTo(spot, input.text.toString()) }
            .setNegativeButton("取消", null)
            .create()
        input.setOnEditorActionListener { _, actionId, event ->
            val confirm = actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_NEXT ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (confirm) {
                connectTo(spot, input.text.toString())
                dialog.dismiss()
            }
            confirm
        }
        dialog.show()
    }

    private fun connectTo(spot: WifiHelper.Hotspot, password: String) {
        if (wifi.connect(spot, password)) {
            AppLog.i("正在连接 Wi-Fi：${spot.ssid}")
            toast("正在连接 ${spot.ssid}…")
        } else {
            AppLog.e("连接 ${spot.ssid} 失败：系统拒绝了网络配置")
            toast("连接失败：系统拒绝了这个网络配置，可改用「打开系统 Wi-Fi 设置」")
        }
    }

    // ---- playback progress overlay (shown briefly on seek / pause) ----

    private lateinit var seekPanel: LinearLayout
    private lateinit var seekText: TextView
    private lateinit var seekProgress: ProgressBar
    private var seekNote: String? = null
    private val hideSeek = Runnable { seekPanel.visibility = View.GONE }
    private val tickSeek = object : Runnable {
        override fun run() {
            if (seekPanel.visibility != View.VISIBLE) return
            updateSeekInfo()
            main.postDelayed(this, 500)
        }
    }

    private fun buildSeekPanel(): LinearLayout {
        seekText = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        seekProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        seekPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#B3000000"))
            }
            visibility = View.GONE
            addView(seekText, LinearLayout.LayoutParams(-1, -2))
            addView(seekProgress, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(10) })
        }
        return seekPanel
    }

    private fun fmtTime(ms: Long): String {
        val t = (ms.coerceAtLeast(0) / 1000)
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60)
        else "%02d:%02d".format(t / 60, t % 60)
    }

    private fun updateSeekInfo() {
        val p = attached?.player ?: return
        val dur = if (p.duration == C.TIME_UNSET) 0L else p.duration
        val pos = p.currentPosition.coerceAtLeast(0)
        val icon = if (p.playWhenReady) "▶" else "⏸"
        val time = if (dur > 0) "$icon  ${fmtTime(pos)} / ${fmtTime(dur)}" else "$icon  直播"
        seekText.text = seekNote?.let { "$it\n$time" } ?: time
        seekProgress.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        seekProgress.visibility = if (dur > 0) View.VISIBLE else View.GONE
    }

    /** Show the progress panel for a few seconds, with an optional one-line note ("快进 10 秒"). */
    private fun showSeekInfo(note: String? = null) {
        seekNote = note
        seekPanel.visibility = View.VISIBLE
        updateSeekInfo()
        main.removeCallbacks(hideSeek)
        main.removeCallbacks(tickSeek)
        main.postDelayed(tickSeek, 500)
        main.postDelayed(hideSeek, 3500)
    }

    // ---- keys ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val svc = attached

        // While something plays: left/right = back/forward 10 s (held: faster), the media keys = 30 s.
        if (svc != null && svc.player.playbackState != Player.STATE_IDLE) {
            val step = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> 10_000L
                KeyEvent.KEYCODE_DPAD_LEFT -> -10_000L
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> 30_000L
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> -30_000L
                else -> 0L
            }
            if (step != 0L) {
                // A held key repeats very fast; act on every 4th repeat and make those jumps bigger.
                if (event.repeatCount > 0 && event.repeatCount % 4 != 0) return true
                val s = if (event.repeatCount > 0) step * 3 else step
                val ok = svc.seekBy(s)
                val secs = kotlin.math.abs(s) / 1000
                showSeekInfo(
                    if (ok) (if (s > 0) "快进 $secs 秒" else "后退 $secs 秒")
                    else "直播流（或不支持拖动的视频）无法快进/后退"
                )
                return true
            }
        }

        // While idle the focused button handles OK itself; this only runs for keys no view consumed.
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                svc?.togglePlayPause()
                if (svc != null && svc.player.playbackState != Player.STATE_IDLE) showSeekInfo()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { svc?.resumePlayback(); showSeekInfo(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { svc?.pausePlayback(); showSeekInfo(); return true }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && svc != null &&
            svc.player.playbackState != Player.STATE_IDLE
        ) {
            svc.stopPlayback()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
