package com.easycast.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
        /** Status meaning "idle, nothing to report"; any other idle status is a notice for the user. */
        const val IDLE_STATUS = "\u0000idle"
        private const val PREFS = "easycast"
        private const val KEY_NAME = "device_name"
        const val MAX_NAME_LENGTH = 30
        private const val KEY_PIN = "web_pin"
        private const val KEY_CASTS = "recent_casts"
        private const val MAX_CASTS = 5
    }

    var deviceName: String = DEFAULT_NAME
        private set

    /** Code needed by the browser page that pushes URLs to this TV; created once, shown on screen. */
    var webPin: String = ""
        private set

    /** One received cast: when it arrived and the stream URL the phone sent. */
    data class Cast(val time: Long, val url: String)

    /** The last few cast URLs, newest first. Kept on disk so they survive a restart. */
    fun recentCasts(): List<Cast> =
        (getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CASTS, null) ?: "")
            .lines()
            .mapNotNull { line ->
                val time = line.substringBefore('|', "").toLongOrNull() ?: return@mapNotNull null
                val url = line.substringAfter('|', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Cast(time, url)
            }

    private fun recordCast(url: String) {
        val list = listOf(Cast(System.currentTimeMillis(), url)) + recentCasts().filter { it.url != url }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_CASTS, list.take(MAX_CASTS).joinToString("\n") { "${it.time}|${it.url}" })
            .apply()
    }

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
                when (state) {
                    Player.STATE_BUFFERING -> AppLog.i("播放器：缓冲中…")
                    Player.STATE_READY -> AppLog.i("播放器：已就绪，开始出画面")
                    Player.STATE_ENDED -> AppLog.i("播放器：播放结束")
                }
                if (state == Player.STATE_READY) setStatus(null)
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                val w = size.width * size.pixelWidthHeightRatio
                val h = size.height.toFloat()
                // Portrait streams may be signalled as rotated 90/270.
                val rotated = size.unappliedRotationDegrees % 180 != 0
                aspect = if (w <= 0f || h <= 0f) 0f else if (rotated) h / w else w / h
                if (size.width > 0) AppLog.i("视频画面 ${size.width}x${size.height}${if (rotated) "（旋转）" else ""}")
                ui?.onAspect(aspect)
            }

            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause?.message ?: error.message
                AppLog.e("播放失败 ${error.errorCodeName}: $cause")
                setStatus("播放失败：${error.errorCodeName}\n请回到抖音重新点击投屏")
            }
        })

        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("easycast").apply {
            setReferenceCounted(false); acquire()
        }

        deviceName = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        webPin = prefs.getString(KEY_PIN, null)?.takeIf { it.length == 4 }
            ?: (1000..9999).random().toString().also { prefs.edit().putString(KEY_PIN, it).apply() }
        renderer = DlnaRenderer(this, deviceName, webPin, this)
        renderer.start()
        AppLog.i("服务已启动：设备名 $deviceName，IP ${renderer.localIp}")
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
        networkCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            } catch (_: IllegalArgumentException) {
            }
        }
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

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Rebuild the DLNA sockets when Wi-Fi/Ethernet drops and comes back (even with the same IP). */
    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                AppLog.i("网络已连接")
                main.removeCallbacks(netChanged)
                main.postDelayed(netChanged, 1500)
            }

            override fun onLost(network: Network) {
                AppLog.w("网络已断开")
                main.post {
                    renderer.onNetworkLost()
                    if (cm.activeNetwork == null && player.playbackState == Player.STATE_IDLE) {
                        setStatus("网络已断开，等待重新连接 Wi-Fi 或网线…")
                    }
                }
            }
        }
        networkCallback = cb
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
    }

    private val netChanged = Runnable {
        Thread {
            renderer.onNetworkChanged()
            main.post { if (player.playbackState == Player.STATE_IDLE) setStatus(idleText()) }
        }.start()
    }

    /** Name of the network the TV is on: the Wi-Fi SSID, or "有线网络" for Ethernet. */
    @Suppress("DEPRECATION")
    fun networkLabel(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "未连接"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                val ssid = wifi.connectionInfo?.ssid?.trim('"')
                if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") "Wi-Fi（名称未知）" else "Wi-Fi：$ssid"
            }
            else -> "其他网络"
        }
    }

    val localIp: String get() = renderer.localIp

    /**
     * Idle status: [IDLE_STATUS] when all is well (the activity draws device name, network and IP itself),
     * or a short notice shown under the header when there is no network.
     */
    fun idleText() =
        if (renderer.localIp == "0.0.0.0") "网络未连接，等待连接 Wi-Fi 或网线…" else IDLE_STATUS

    /** Saves the new name and re-announces the device under it. Main thread. */
    fun renameDevice(newName: String) {
        val name = newName.trim().take(MAX_NAME_LENGTH)
        if (name.isEmpty() || name == deviceName) return
        deviceName = name
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_NAME, name).apply()
        renderer.friendlyName = name
        // Network I/O must stay off the main thread; byebye + alive makes controllers refresh the name.
        AppLog.i("设备名已改为：$name")
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
        AppLog.i("停止播放")
        player.stop()
        player.clearMediaItems()
        aspect = 0f
        pausedAt = 0
        setStatus(idleText())
    }

    fun pausePlayback() {
        if (player.playbackState == Player.STATE_IDLE) return
        AppLog.i("暂停")
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
        AppLog.i("继续播放")
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
            load(uri, autoPlay = false)
            bringUiToFront()
        }
    }

    /**
     * Load a stream. A phone's SetAVTransportURI waits for its own Play command ([autoPlay] false);
     * replaying from the on-screen list starts right away.
     */
    private fun load(url: String, autoPlay: Boolean) {
        currentUri = url
        recordCast(url)
        pausedAt = 0
        player.setMediaItem(MediaItem.fromUri(url))
        player.playWhenReady = autoPlay
        player.prepare()
        setStatus("正在加载直播…")
    }

    /** Play a URL from the recent-casts list again. Main thread. */
    fun replay(url: String) {
        AppLog.i("重新播放：$url")
        load(url, autoPlay = true)
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

    override fun recentUrls() = recentCasts().map { it.time to it.url }

    override fun transportState() = cState
    override fun positionMs() = cPos
    override fun durationMs() = cDur
}
