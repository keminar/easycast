package com.easycast.tv

import android.content.Context
import android.media.AudioManager
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/** Callbacks from the DLNA controller (e.g. Douyin). May arrive on any thread. */
interface RendererListener {
    fun onSetUri(uri: String)
    fun onPlay()
    fun onPause()
    fun onStop()
    fun onSeek(ms: Long)
    /** Recent cast URLs (epoch millis, url), newest first; shown on the web page. */
    fun recentUrls(): List<Pair<Long, String>>
    /** One of PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING. */
    fun transportState(): String
    fun positionMs(): Long
    fun durationMs(): Long
    /** False for live streams and for servers without Range support: there is nothing to seek in. */
    fun seekable(): Boolean
}

/**
 * Minimal UPnP/DLNA MediaRenderer: SSDP discovery + device description + SOAP control
 * for AVTransport, RenderingControl and ConnectionManager.
 */
class DlnaRenderer(
    context: Context,
    @Volatile var friendlyName: String,
    /** Code the web page must send; shown on the TV screen. */
    @Volatile var webPin: String,
    private val listener: RendererListener,
) {
    companion object {
        const val HTTP_PORT = 8200
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val AV = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val CM = "urn:schemas-upnp-org:service:ConnectionManager:1"
        private const val DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val uuid: String = UUID.nameUUIDFromBytes(
        (android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "easycast").toByteArray()
    ).toString()

    @Volatile private var running = false
    private var nif: NetworkInterface? = null
    private var serverSocket: ServerSocket? = null
    private var ssdpSocket: MulticastSocket? = null
    private var pool = Executors.newCachedThreadPool()
    /** Bumped on every (re)start so threads of an old run exit instead of lingering. */
    @Volatile private var gen = 0
    @Volatile private var currentUri = ""
    /** The network went away at some point since the last (re)start: sockets/multicast state may be stale. */
    @Volatile private var sawDown = false
    /** A socket thread died or sends keep failing: needs a restart once a network is available. */
    @Volatile private var failed = false

    var localIp: String = "0.0.0.0"
        private set

    private val targets get() = listOf(
        "upnp:rootdevice", "uuid:$uuid", DEVICE, AV, RC, CM
    )

    @Synchronized
    fun start() {
        if (running) return
        val (n, ip) = findInterface()
        nif = n
        localIp = ip ?: "0.0.0.0"
        sawDown = false
        failed = false
        running = true
        val g = ++gen
        pool = Executors.newCachedThreadPool()
        pool.execute { runHttp(g) }
        pool.execute { runSsdp(g) }
        pool.execute { runAlive(g) }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try { sendNotify("ssdp:byebye") } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        try { ssdpSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        ssdpSocket = null
        pool.shutdownNow()
    }

    fun restart() {
        stop()
        start()
    }

    /** The connection dropped. Remember it so the next reconnect rebuilds sockets even with the same IP. */
    fun onNetworkLost() {
        sawDown = true
    }

    /**
     * Call when a network comes up. Restarts (re-binds sockets, re-joins multicast, re-announces) if the
     * address changed, or if we lost the connection / a socket failed since the last start.
     */
    fun onNetworkChanged() {
        if (!running) return
        val (_, ip) = findInterface()
        if (ip != null && (ip != localIp || sawDown || failed)) restart()
    }

    // ---------------------------------------------------------------- network

    private fun findInterface(): Pair<NetworkInterface?, String?> {
        for (n in NetworkInterface.getNetworkInterfaces()) {
            if (!n.isUp || n.isLoopback || n.isVirtual || !n.supportsMulticast()) continue
            for (a in n.inetAddresses) {
                if (a is Inet4Address && a.isSiteLocalAddress) {
                    return n to (a.hostAddress ?: continue)
                }
            }
        }
        return null to null
    }

    private val location get() = "http://$localIp:$HTTP_PORT/desc.xml"
    private val serverHeader = "Android/8.0 UPnP/1.0 EasyCast/1.0"

    private fun usn(t: String) = if (t == "uuid:$uuid") t else "uuid:$uuid::$t"

    // ---------------------------------------------------------------- SSDP

    private fun runSsdp(g: Int) {
        try {
            val group = InetAddress.getByName(SSDP_ADDR)
            val s = MulticastSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(SSDP_PORT))
            s.timeToLive = 4
            nif?.let { s.networkInterface = it; s.joinGroup(InetSocketAddress(group, SSDP_PORT), it) }
                ?: s.joinGroup(group)
            ssdpSocket = s
            val buf = ByteArray(2048)
            while (running && gen == g) {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val text = String(p.data, 0, p.length)
                if (!text.startsWith("M-SEARCH")) continue
                val st = Regex("(?im)^ST:\\s*(.+?)\\s*$").find(text)?.groupValues?.get(1) ?: continue
                val replies = if (st.equals("ssdp:all", true)) targets
                else targets.filter { it.equals(st, true) }
                AppLog.i("收到搜索 ${p.address.hostAddress}  ST=$st  回应 ${replies.size} 条")
                val addr = p.address
                val port = p.port
                val packets = replies.map { t ->
                    ("HTTP/1.1 200 OK\r\n" +
                        "CACHE-CONTROL: max-age=1800\r\nEXT:\r\n" +
                        "LOCATION: $location\r\nSERVER: $serverHeader\r\n" +
                        "ST: $t\r\nUSN: ${usn(t)}\r\n\r\n").toByteArray()
                }
                fun sendAll() {
                    for (b in packets) s.send(DatagramPacket(b, b.size, addr, port))
                }
                sendAll()
                // UDP is lossy; answer twice so a single dropped reply doesn't cost a retry round.
                pool.execute {
                    try { Thread.sleep(250); sendAll() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            if (running && gen == g) {
                // The SSDP socket is dead (network dropped, bind/join failed); the watchdog restarts us.
                failed = true
                AppLog.w("SSDP 搜索线程已停止: ${e.message}，将自动重启")
            }
        }
    }

    private fun runAlive(g: Int) {
        try {
            // Wait until the SSDP socket is bound, otherwise the first announcements are lost.
            while (running && gen == g && ssdpSocket == null && !failed) Thread.sleep(50)
            var n = 0
            while (running && gen == g) {
                // A failed send (e.g. network unreachable while Wi-Fi is down) must not kill this loop.
                try {
                    sendNotify("ssdp:alive")
                } catch (e: Exception) {
                    failed = true
                    AppLog.w("广播通知发送失败: ${e.message}")
                }
                // Dense announcements at first so controllers see us immediately, then keep-alives.
                val wait = when {
                    n < 3 -> 400L
                    n < 23 -> 3_000L
                    else -> 5_000L
                }
                n++
                Thread.sleep(wait)
                // Re-send the IGMP membership report so a snooping switch keeps forwarding multicast to us.
                if (n % 5 == 0) rejoinGroup()

                // Watchdog: recover even if the system never delivered a connectivity callback.
                val (_, ip) = findInterface()
                if (ip == null) {
                    sawDown = true
                } else if (ip != localIp || sawDown || failed) {
                    AppLog.i("网络恢复/变化 ($localIp -> $ip)，重启投屏服务")
                    // Restart from a fresh thread: stop() shuts down this very pool.
                    Thread { restart() }.start()
                    return
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (running && gen == g) AppLog.e("服务线程异常: ${e.message}")
        }
    }

    private fun rejoinGroup() {
        val s = ssdpSocket ?: return
        val face = nif ?: return
        try {
            val sa = InetSocketAddress(InetAddress.getByName(SSDP_ADDR), SSDP_PORT)
            s.leaveGroup(sa, face)
            s.joinGroup(sa, face)
        } catch (_: Exception) {
        }
    }

    private fun sendNotify(nts: String) {
        val s = ssdpSocket ?: return
        val group = InetAddress.getByName(SSDP_ADDR)
        // Also hit the subnet broadcast address: some APs/switches delay or drop multicast.
        val broadcast = nif?.interfaceAddresses?.firstOrNull { it.address is Inet4Address }?.broadcast
        for (t in targets) {
            val msg = "NOTIFY * HTTP/1.1\r\nHOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                "CACHE-CONTROL: max-age=1800\r\nLOCATION: $location\r\n" +
                "NT: $t\r\nNTS: $nts\r\nSERVER: $serverHeader\r\nUSN: ${usn(t)}\r\n\r\n"
            val b = msg.toByteArray()
            s.send(DatagramPacket(b, b.size, group, SSDP_PORT))
            if (broadcast != null && nts == "ssdp:alive") {
                try {
                    s.broadcast = true
                    s.send(DatagramPacket(b, b.size, broadcast, SSDP_PORT))
                } catch (_: Exception) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- HTTP

    private fun runHttp(g: Int) {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(HTTP_PORT))
            serverSocket = ss
            while (running && gen == g) {
                val c = ss.accept()
                pool.execute { handle(c) }
            }
        } catch (e: Exception) {
            if (running && gen == g) AppLog.e("服务线程异常: ${e.message}")
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (c != '\r'.code) sb.append(c.toChar())
        }
    }

    private fun handle(sock: Socket) {
        sock.use {
            it.soTimeout = 10_000
            val inp = BufferedInputStream(it.getInputStream())
            val reqLine = readLine(inp) ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = originForm(parts[1])
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            // Only the device description from other devices is interesting; the TV's own system
            // keeps re-reading it (and the service files) every second.
            val from = it.inetAddress.hostAddress
            if (path == "/desc.xml" && from != localIp) AppLog.i("设备描述请求 来自 $from")
            val headers = HashMap<String, String>()
            while (true) {
                val l = readLine(inp) ?: break
                if (l.isEmpty()) break
                val idx = l.indexOf(':')
                if (idx > 0) headers[l.substring(0, idx).trim().lowercase()] = l.substring(idx + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val buf = ByteArray(len)
            var n = 0
            while (n < len) {
                val r = inp.read(buf, n, len - n)
                if (r < 0) break
                n += r
            }
            val body = String(buf, 0, n, Charsets.UTF_8)

            val out = it.getOutputStream()
            fun reply(code: String, type: String, content: String, extra: String = "") {
                val b = content.toByteArray(Charsets.UTF_8)
                out.write(
                    ("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\n" +
                        "Server: $serverHeader\r\nConnection: close\r\n$extra\r\n").toByteArray()
                )
                out.write(b)
                out.flush()
            }

            val xml = "text/xml; charset=\"utf-8\""
            when {
                method == "GET" && (path == "/" || path == "/index.html") ->
                    reply("200 OK", "text/html; charset=utf-8", WebPage.html)
                path.startsWith("/api/") -> {
                    val (code, json) = handleApi(path, parseForm("$query&$body"), from)
                    reply(code, "application/json; charset=utf-8", json)
                }
                method == "GET" && path == "/desc.xml" -> reply("200 OK", xml, deviceXml())
                method == "GET" && path == "/AVTransport.xml" -> reply("200 OK", xml, scpd(avActions))
                method == "GET" && path == "/RenderingControl.xml" -> reply("200 OK", xml, scpd(rcActions))
                method == "GET" && path == "/ConnectionManager.xml" -> reply("200 OK", xml, scpd(cmActions))
                method == "SUBSCRIBE" -> reply(
                    "200 OK", "text/plain", "",
                    "SID: uuid:${UUID.randomUUID()}\r\nTIMEOUT: Second-1800\r\n"
                )
                method == "UNSUBSCRIBE" -> reply("200 OK", "text/plain", "")
                method == "POST" && path.startsWith("/ctl/") -> {
                    val soapAction = headers["soapaction"]?.trim('"') ?: ""
                    val service = soapAction.substringBefore('#')
                    val action = soapAction.substringAfter('#')
                    val result = try { dispatch(service, action, body) } catch (e: Exception) { null }
                    if (result == null) {
                        reply("500 Internal Server Error", xml, soapError(401, "Invalid Action"))
                    } else {
                        reply("200 OK", xml, soapEnvelope(service, action, result))
                    }
                }
                else -> reply("404 Not Found", "text/plain", "not found")
            }
        }
    }

    // ---------------------------------------------------------------- web page API

    private var pinFails = 0
    private var pinLockedUntil = 0L

    private fun parseForm(s: String): Map<String, String> =
        s.split('&').filter { '=' in it }.associate {
            val k = java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8")
            val v = java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
            k to v
        }

    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "") + "\""

    private fun error(msg: String) = "{\"error\":${json(msg)}}"

    /** Returns (HTTP status, JSON body). The page must send the PIN shown on the TV with every call. */
    private fun handleApi(path: String, params: Map<String, String>, from: String?): Pair<String, String> {
        synchronized(this) {
            if (System.currentTimeMillis() < pinLockedUntil) return "429 Too Many Requests" to error("口令错误次数过多，请 1 分钟后再试")
            if (webPin.isNotEmpty() && params["pin"] != webPin) {
                AppLog.w("网页推送口令错误，来自 $from")
                if (++pinFails >= 5) {
                    pinFails = 0
                    pinLockedUntil = System.currentTimeMillis() + 60_000
                }
                return "403 Forbidden" to error("口令不对（口令每天自动更换，请看电视主界面上的新口令）")
            }
            pinFails = 0
        }
        return when (path) {
            "/api/state" ->
                "200 OK" to "{\"state\":${json(listener.transportState())},\"uri\":${json(currentUri)}," +
                    "\"device\":${json(friendlyName)},\"ip\":${json(localIp)}," +
                    "\"pos\":${listener.positionMs()},\"dur\":${listener.durationMs()},\"seekable\":${listener.seekable()}}"
            "/api/seek" -> {
                if (!listener.seekable()) {
                    "409 Conflict" to error("这个视频不能快进/后退（直播流，或文件服务器不支持 Range）")
                } else {
                    val dur = listener.durationMs()
                    val raw = params["ms"]?.toLongOrNull()
                        ?: (listener.positionMs() + (params["delta"]?.toLongOrNull() ?: 0L))
                    // Stay a second short of the end so a seek to "the end" does not just stop playback.
                    val target = raw.coerceIn(0L, if (dur > 0) maxOf(dur - 1000, 0L) else Long.MAX_VALUE)
                    AppLog.i("网页操作：跳到 ${target / 1000} 秒")
                    listener.onSeek(target)
                    "200 OK" to "{\"ok\":true,\"pos\":$target}"
                }
            }
            "/api/recent" ->
                "200 OK" to listener.recentUrls().joinToString(",", "[", "]") { (t, u) -> "{\"time\":$t,\"url\":${json(u)}}" }
            "/api/play" -> {
                val url = params["url"]?.trim().orEmpty()
                if (listOf("http://", "https://", "rtmp://").none { url.startsWith(it, ignoreCase = true) }) {
                    "400 Bad Request" to error("地址要以 http://、https:// 或 rtmp:// 开头")
                } else {
                    AppLog.i("网页推送：$url  来自 $from")
                    currentUri = url
                    listener.onSetUri(url)
                    listener.onPlay()
                    "200 OK" to "{\"ok\":true}"
                }
            }
            "/api/pause" -> { AppLog.i("网页操作：暂停"); listener.onPause(); "200 OK" to "{\"ok\":true}" }
            "/api/resume" -> { AppLog.i("网页操作：继续"); listener.onPlay(); "200 OK" to "{\"ok\":true}" }
            "/api/stop" -> { AppLog.i("网页操作：停止"); listener.onStop(); "200 OK" to "{\"ok\":true}" }
            else -> "404 Not Found" to error("没有这个接口")
        }
    }

    // ---------------------------------------------------------------- SOAP

    private fun arg(body: String, name: String): String? =
        Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1)?.let { unescape(it) }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun fmt(ms: Long): String {
        val t = (ms.coerceAtLeast(0) / 1000)
        return "%02d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60)
    }

    private fun parseTime(s: String): Long {
        val p = s.substringBefore('.').split(":").mapNotNull { it.toLongOrNull() }
        return if (p.size == 3) (p[0] * 3600 + p[1] * 60 + p[2]) * 1000 else 0
    }

    private fun tag(name: String, value: String) = "<$name>${esc(value)}</$name>"

    /** Returns inner XML of the response, or null if the action is unknown. */
    private fun dispatch(service: String, action: String, body: String): String? {
        if (action != "GetPositionInfo" && action != "GetTransportInfo") {
            AppLog.i("控制命令 $action ${if (action == "SetAVTransportURI") arg(body, "CurrentURI") else ""}")
        }
        when (service) {
            AV -> return when (action) {
                "SetAVTransportURI" -> {
                    currentUri = arg(body, "CurrentURI") ?: ""
                    listener.onSetUri(currentUri); ""
                }
                "Play" -> { listener.onPlay(); "" }
                "Pause" -> { listener.onPause(); "" }
                "Stop" -> { listener.onStop(); "" }
                "Seek" -> {
                    if (arg(body, "Unit") == "REL_TIME") listener.onSeek(parseTime(arg(body, "Target") ?: ""))
                    ""
                }
                "GetTransportInfo" ->
                    tag("CurrentTransportState", listener.transportState()) +
                        tag("CurrentTransportStatus", "OK") + tag("CurrentSpeed", "1")
                "GetPositionInfo" ->
                    tag("Track", "1") + tag("TrackDuration", fmt(listener.durationMs())) +
                        tag("TrackMetaData", "") + tag("TrackURI", currentUri) +
                        tag("RelTime", fmt(listener.positionMs())) +
                        tag("AbsTime", fmt(listener.positionMs())) +
                        tag("RelCount", "2147483647") + tag("AbsCount", "2147483647")
                "GetMediaInfo" ->
                    tag("NrTracks", "1") + tag("MediaDuration", fmt(listener.durationMs())) +
                        tag("CurrentURI", currentUri) + tag("CurrentURIMetaData", "") +
                        tag("NextURI", "NOT_IMPLEMENTED") + tag("NextURIMetaData", "") +
                        tag("PlayMedium", "NETWORK") + tag("RecordMedium", "NOT_IMPLEMENTED") +
                        tag("WriteStatus", "NOT_IMPLEMENTED")
                "GetCurrentTransportActions" -> tag("Actions", "Play,Pause,Stop,Seek")
                else -> null
            }
            RC -> return when (action) {
                "GetVolume" -> {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    tag("CurrentVolume", (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max).toString())
                }
                "SetVolume" -> {
                    val v = arg(body, "DesiredVolume")?.toIntOrNull() ?: 0
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceIn(0, 100) * max / 100, 0); ""
                }
                "GetMute" -> tag("CurrentMute", if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) "1" else "0")
                "SetMute" -> {
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        if (arg(body, "DesiredMute") == "1") AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                        0
                    ); ""
                }
                else -> null
            }
            CM -> return when (action) {
                "GetProtocolInfo" -> tag("Source", "") + tag(
                    "Sink",
                    "http-get:*:video/mp4:*,http-get:*:video/x-flv:*,http-get:*:application/vnd.apple.mpegurl:*," +
                        "http-get:*:application/x-mpegURL:*,http-get:*:video/mp2t:*"
                )
                "GetCurrentConnectionIDs" -> tag("ConnectionIDs", "0")
                else -> null
            }
        }
        return null
    }

    private fun soapEnvelope(service: String, action: String, inner: String) =
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:${action}Response xmlns:u=\"$service\">$inner</u:${action}Response></s:Body></s:Envelope>"

    private fun soapError(code: Int, desc: String) =
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><s:Fault>" +
            "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode>" +
            "<errorDescription>$desc</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"

    // ---------------------------------------------------------------- descriptions

    private fun deviceXml(): String {
        fun svc(type: String, id: String) =
            "<service><serviceType>$type</serviceType><serviceId>urn:upnp-org:serviceId:$id</serviceId>" +
                "<SCPDURL>/$id.xml</SCPDURL><controlURL>/ctl/$id</controlURL><eventSubURL>/evt/$id</eventSubURL></service>"
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<URLBase>http://$localIp:$HTTP_PORT/</URLBase><device>" +
            "<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>" +
            "<deviceType>$DEVICE</deviceType><friendlyName>${esc(friendlyName)}</friendlyName>" +
            "<manufacturer>EasyCast</manufacturer><manufacturerURL>http://localhost/</manufacturerURL>" +
            "<modelDescription>EasyCast DLNA Renderer</modelDescription>" +
            "<modelName>EasyCast Renderer</modelName><modelNumber>1.0</modelNumber>" +
            "<serialNumber>$uuid</serialNumber>" +
            "<UDN>uuid:$uuid</UDN><serviceList>" +
            svc(AV, "AVTransport") + svc(RC, "RenderingControl") + svc(CM, "ConnectionManager") +
            "</serviceList></device></root>"
    }

    /** "in:Arg:RelatedVar" / "out:Arg:RelatedVar" */
    private fun act(name: String, vararg args: String) = name to args.toList()

    private val iid = "in:InstanceID:A_ARG_TYPE_InstanceID"

    private val avActions = listOf(
        act("SetAVTransportURI", iid, "in:CurrentURI:AVTransportURI", "in:CurrentURIMetaData:AVTransportURIMetaData"),
        act("GetMediaInfo", iid, "out:NrTracks:NumberOfTracks", "out:MediaDuration:CurrentMediaDuration",
            "out:CurrentURI:AVTransportURI", "out:CurrentURIMetaData:AVTransportURIMetaData",
            "out:NextURI:NextAVTransportURI", "out:NextURIMetaData:NextAVTransportURIMetaData",
            "out:PlayMedium:PlaybackStorageMedium", "out:RecordMedium:RecordStorageMedium",
            "out:WriteStatus:RecordMediumWriteStatus"),
        act("GetTransportInfo", iid, "out:CurrentTransportState:TransportState",
            "out:CurrentTransportStatus:TransportStatus", "out:CurrentSpeed:TransportPlaySpeed"),
        act("GetPositionInfo", iid, "out:Track:CurrentTrack", "out:TrackDuration:CurrentTrackDuration",
            "out:TrackMetaData:CurrentTrackMetaData", "out:TrackURI:CurrentTrackURI",
            "out:RelTime:RelativeTimePosition", "out:AbsTime:AbsoluteTimePosition",
            "out:RelCount:RelativeCounterPosition", "out:AbsCount:AbsoluteCounterPosition"),
        act("GetCurrentTransportActions", iid, "out:Actions:CurrentTransportActions"),
        act("Play", iid, "in:Speed:TransportPlaySpeed"),
        act("Pause", iid),
        act("Stop", iid),
        act("Seek", iid, "in:Unit:A_ARG_TYPE_SeekMode", "in:Target:A_ARG_TYPE_SeekTarget"),
    )

    private val rcActions = listOf(
        act("GetVolume", iid, "in:Channel:A_ARG_TYPE_Channel", "out:CurrentVolume:Volume"),
        act("SetVolume", iid, "in:Channel:A_ARG_TYPE_Channel", "in:DesiredVolume:Volume"),
        act("GetMute", iid, "in:Channel:A_ARG_TYPE_Channel", "out:CurrentMute:Mute"),
        act("SetMute", iid, "in:Channel:A_ARG_TYPE_Channel", "in:DesiredMute:Mute"),
    )

    private val cmActions = listOf(
        act("GetProtocolInfo", "out:Source:SourceProtocolInfo", "out:Sink:SinkProtocolInfo"),
        act("GetCurrentConnectionIDs", "out:ConnectionIDs:CurrentConnectionIDs"),
    )

    private fun scpd(actions: List<Pair<String, List<String>>>): String {
        val vars = LinkedHashSet<String>()
        val sb = StringBuilder(
            "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
                "<specVersion><major>1</major><minor>0</minor></specVersion><actionList>"
        )
        for ((name, args) in actions) {
            sb.append("<action><name>$name</name><argumentList>")
            for (a in args) {
                val (dir, argName, related) = a.split(":")
                vars += related
                sb.append("<argument><name>$argName</name><direction>$dir</direction>" +
                    "<relatedStateVariable>$related</relatedStateVariable></argument>")
            }
            sb.append("</argumentList></action>")
        }
        sb.append("</actionList><serviceStateTable>")
        for (v in vars) {
            val type = when (v) {
                "Volume" -> "ui2"
                "Mute" -> "boolean"
                else -> "string"
            }
            sb.append("<stateVariable sendEvents=\"no\"><name>$v</name><dataType>$type</dataType></stateVariable>")
        }
        sb.append("</serviceStateTable></scpd>")
        return sb.toString()
    }
}
