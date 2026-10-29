package com.easycast.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Wi-Fi controls available to a sideloaded app targeting API 26 on Android 8.0.
 * Apps targeting API 29+ lose most of these, so keep targetSdk below 29.
 */
@Suppress("DEPRECATION")
class WifiHelper(context: Context) {

    private val ctx = context.applicationContext
    private val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())

    /** [saved]: the TV has connected to this network before, so it can be joined without typing a password. */
    data class Hotspot(
        val ssid: String,
        val secured: Boolean,
        val unsupported: Boolean,
        val level: Int,
        val capabilities: String,
        val saved: Boolean = false,
    )

    /** Drop the current connection and join again; also switches Wi-Fi on if it was off. */
    fun reconnect() {
        if (!wifi.isWifiEnabled) {
            wifi.isWifiEnabled = true
            return
        }
        wifi.disconnect()
        wifi.reconnect()
    }

    /** Scan and deliver unique hotspots, strongest first. Falls back to cached results after a timeout. */
    fun scan(onResult: (List<Hotspot>) -> Unit) {
        if (!wifi.isWifiEnabled) wifi.isWifiEnabled = true
        var done = false
        lateinit var receiver: BroadcastReceiver
        fun finish() {
            if (done) return
            done = true
            try { ctx.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
            onResult(toHotspots(wifi.scanResults.orEmpty()))
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = finish()
        }
        ctx.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        if (!wifi.startScan()) {
            finish()
            return
        }
        main.postDelayed({ finish() }, 8000)
    }

    private fun toHotspots(results: List<ScanResult>): List<Hotspot> {
        // The system never reveals saved passwords to apps, but the SSIDs themselves are listed.
        val savedSsids = try {
            wifi.configuredNetworks.orEmpty().map { it.SSID.trim('"') }.toSet()
        } catch (_: SecurityException) {
            emptySet()
        }
        return results.filter { !it.SSID.isNullOrBlank() }
            .groupBy { it.SSID }
            .map { (ssid, list) ->
                val best = list.maxByOrNull { it.level }!!
                val caps = best.capabilities
                Hotspot(
                    ssid = ssid,
                    secured = caps.contains("WEP") || caps.contains("PSK") || caps.contains("EAP"),
                    unsupported = caps.contains("EAP"), // enterprise networks need more than a password
                    level = WifiManager.calculateSignalLevel(best.level, 5),
                    capabilities = caps,
                    saved = ssid in savedSsids,
                )
            }
            .sortedByDescending { it.level }
    }

    /**
     * Add (or reuse) a network configuration and connect to it.
     * Returns false if the system refused the configuration.
     */
    fun connect(spot: Hotspot, password: String): Boolean {
        val quoted = "\"${spot.ssid}\""
        if (!wifi.isWifiEnabled) wifi.isWifiEnabled = true

        val existing = wifi.configuredNetworks?.firstOrNull { it.SSID == quoted }
        val id = if (existing != null && password.isEmpty()) {
            existing.networkId
        } else {
            existing?.let { wifi.removeNetwork(it.networkId) }
            val conf = WifiConfiguration().apply {
                SSID = quoted
                when {
                    !spot.secured -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    spot.capabilities.contains("WEP") -> {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                        allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                        allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
                        wepKeys[0] = "\"$password\""
                        wepTxKeyIndex = 0
                    }
                    else -> {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                        preSharedKey = "\"$password\""
                    }
                }
            }
            wifi.addNetwork(conf)
        }
        if (id == -1) return false
        wifi.disconnect()
        val ok = wifi.enableNetwork(id, true)
        wifi.reconnect()
        wifi.saveConfiguration()
        return ok
    }

    /** Open the TV's own Wi-Fi settings page; falls back to the general settings. */
    fun openSystemSettings(from: Context): Boolean {
        for (action in listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                from.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }
}
