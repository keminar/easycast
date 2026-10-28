package com.easycast.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Full-screen view onto the player owned by [DlnaService]. Cast from Douyin's live room
 * ("投屏" button) to this TV; the service receives the stream URL and starts this screen.
 */
class MainActivity : Activity(), DlnaService.Ui {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var surface: SurfaceView
    private var aspect = 0f
    private var attached: DlnaService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
        }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(status, FrameLayout.LayoutParams(-1, -1))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSurface() }
        setContentView(root)

        // Make sure the background service is up (it normally already is).
        startForegroundService(Intent(this, DlnaService::class.java))
    }

    override fun onStart() {
        super.onStart()
        attach()
    }

    override fun onStop() {
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
        if (text == null) {
            status.visibility = View.GONE
        } else {
            status.text = text
            status.visibility = View.VISIBLE
        }
    }

    override fun onAspect(aspect: Float) {
        this.aspect = aspect
        fitSurface()
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

    // ---- keys ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val svc = attached
        val idle = svc != null && svc.player.playbackState == androidx.media3.common.Player.STATE_IDLE
        if (svc != null && (keyCode == KeyEvent.KEYCODE_MENU ||
                (idle && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)))
        ) {
            if (idle) showRenameDialog(svc)
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                svc?.togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { svc?.resumePlayback(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { svc?.pausePlayback(); return true }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && svc != null &&
            svc.player.playbackState != androidx.media3.common.Player.STATE_IDLE
        ) {
            svc.stopPlayback()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
