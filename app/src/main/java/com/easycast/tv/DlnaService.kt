package com.easycast.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Keeps the DLNA renderer alive in the background (and after boot) so the TV shows up in
 * the cast list even when the UI is closed. Owns the player; [MainActivity] only attaches
 * a surface to it.
 */
class DlnaService : Service(), RendererListener {

    interface Ui {
        fun onStatus(text: String?)
        fun onAspect(aspect: Float)
    }

    companion object {
        @Volatile var instance: DlnaService? = null
        private const val CHANNEL = "easycast"
        const val DEFAULT_NAME = "EasyCast"
        private const val PREFS = "easycast"
        private const val KEY_NAME = "device_name"
        const val MAX_NAME_LENGTH = 30
    }

    var deviceName: String = DEFAULT_NAME
        private set

    private val main = Handler(Looper.getMainLooper())
    lateinit var player: ExoPlayer
        private set
    private lateinit var renderer: DlnaRenderer
    private var multicastLock: WifiManager.MulticastLock? = null
    private var currentUri: String? = null
    private var pausedAt = 0L

    /** Set by the activity; main thread only. */
    var ui: Ui? = null
    var status: String? = null
        private set
    var aspect = 0f
        private set

    // Snapshot for the DLNA threads (ExoPlayer may only be touched on the main thread).
    @Volatile private var cState = "STOPPED"
    @Volatile private var cPos = 0L
    @Volatile private var cDur = 0L

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 8.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(8000)
        // Small buffers keep live-stream latency low.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1000, 8000, 500, 1000)
            .build()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(loadControl)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) setStatus(null)
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                val w = size.width * size.pixelWidthHeightRatio
                val h = size.height.toFloat()
                // Portrait streams may be signalled as rotated 90/270.
                val rotated = size.unappliedRotationDegrees % 180 != 0
                aspect = if (w <= 0f || h <= 0f) 0f else if (rotated) h / w else w / h
                ui?.onAspect(aspect)
            }

            override fun onPlayerError(error: PlaybackException) {
                setStatus("播放失败：${error.errorCodeName}\n请回到抖音重新点击投屏")
            }
        })

        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("easycast").apply {
            setReferenceCounted(false); acquire()
        }

        deviceName = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
        renderer = DlnaRenderer(this, deviceName, this)
        renderer.start()
        watchNetwork()
        setStatus(idleText())
        instance = this
        main.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        renderer.stop()
        player.release()
        multicastLock?.release()
        super.onDestroy()
    }

    // ---- helpers ----

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "投屏接收", NotificationManager.IMPORTANCE_MIN)
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("简易投屏接收中")
            .setContentText("可在抖音中选择本设备投屏")
            .build()
    }

    /** Re-announce on a new IP when Wi-Fi/Ethernet reconnects. */
    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.removeCallbacks(netChanged)
                main.postDelayed(netChanged, 1500)
            }
        })
    }

    private val netChanged = Runnable {
        Thread {
            renderer.onNetworkChanged()
            main.post { if (player.playbackState == Player.STATE_IDLE) setStatus(idleText()) }
        }.start()
    }

    fun idleText() =
        "简易投屏 接收端\n\n设备名：$deviceName\nIP：${renderer.localIp}\n\n" +
            "打开抖音直播间 → 点击「投屏」→ 选择本设备\n\n按遥控器「确认键」可修改设备名称"

    /** Saves the new name and re-announces the device under it. Main thread. */
    fun renameDevice(newName: String) {
        val name = newName.trim().take(MAX_NAME_LENGTH)
        if (name.isEmpty() || name == deviceName) return
        deviceName = name
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_NAME, name).apply()
        renderer.friendlyName = name
        // Network I/O must stay off the main thread; byebye + alive makes controllers refresh the name.
        Thread { renderer.restart() }.start()
        if (player.playbackState == Player.STATE_IDLE) setStatus(idleText())
    }

    private fun setStatus(text: String?) {
        status = text
        ui?.onStatus(text)
    }

    private fun bringUiToFront() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        )
    }

    fun stopPlayback() {
        player.stop()
        player.clearMediaItems()
        aspect = 0f
        pausedAt = 0
        setStatus(idleText())
    }

    fun pausePlayback() {
        if (player.playbackState == Player.STATE_IDLE) return
        player.playWhenReady = false
        pausedAt = SystemClock.elapsedRealtime()
        if (player.playbackState == Player.STATE_READY) setStatus("⏸ 已暂停")
    }

    fun resumePlayback() {
        if (player.playbackState == Player.STATE_IDLE && currentUri == null) return
        val live = player.isCurrentMediaItemLive || player.duration == C.TIME_UNSET
        val uri = currentUri
        // A paused live stream is stale after a few seconds: reload to jump back to the live edge.
        if (pausedAt > 0 && live && uri != null && SystemClock.elapsedRealtime() - pausedAt > 5000) {
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            setStatus("正在回到直播…")
        }
        pausedAt = 0
        player.playWhenReady = true
        if (player.playbackState == Player.STATE_READY) setStatus(null)
    }

    fun togglePlayPause() {
        if (player.playbackState == Player.STATE_IDLE) return
        if (player.playWhenReady) pausePlayback() else resumePlayback()
    }

    private val ticker = object : Runnable {
        override fun run() {
            cState = when (player.playbackState) {
                Player.STATE_BUFFERING -> "TRANSITIONING"
                Player.STATE_READY -> if (player.playWhenReady) "PLAYING" else "PAUSED_PLAYBACK"
                else -> "STOPPED"
            }
            cPos = player.currentPosition.coerceAtLeast(0)
            cDur = if (player.duration == C.TIME_UNSET) 0 else player.duration
            main.postDelayed(this, 500)
        }
    }

    // ---- RendererListener (called from DLNA threads) ----

    override fun onSetUri(uri: String) {
        main.post {
            currentUri = uri
            pausedAt = 0
            player.setMediaItem(MediaItem.fromUri(uri))
            player.playWhenReady = false
            player.prepare()
            setStatus("正在加载直播…")
            bringUiToFront()
        }
    }

    override fun onPlay() {
        main.post { resumePlayback() }
    }

    override fun onPause() {
        main.post { pausePlayback() }
    }

    override fun onStop() {
        main.post { stopPlayback() }
    }

    override fun onSeek(ms: Long) {
        main.post { if (player.isCurrentMediaItemSeekable) player.seekTo(ms) }
    }

    override fun transportState() = cState
    override fun positionMs() = cPos
    override fun durationMs() = cDur
}
