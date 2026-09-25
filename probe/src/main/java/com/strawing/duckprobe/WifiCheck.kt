package com.strawing.duckprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.util.Log

class WifiCheck : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        fun say(line: String) = Log.i("DuckProbe", line)
        say("WIFI — what this app can read about nearby networks")

        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        if (wifi == null) {
            say("  no WifiManager")
            return
        }

        @Suppress("DEPRECATION")
        val info = runCatching { wifi.connectionInfo }.getOrNull()
        say("  connected BSSID    = ${info?.bssid ?: "null"}")
        say("  connected SSID     = ${info?.ssid ?: "null"}")
        say("  rssi               = ${info?.rssi ?: "?"}")

        val scans = runCatching { wifi.scanResults }.getOrNull()
        say("  scan results       = ${scans?.size ?: "refused"}")
        scans?.take(3)?.forEach { say("    ${it.BSSID}  ${it.SSID}") }

        val leak = info?.bssid?.takeIf { it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" }
        if (leak != null) {
            say("  VERDICT: real access point $leak is visible — that pins the true position")
        } else if ((scans?.size ?: 0) > 0) {
            say("  VERDICT: scan list still leaks ${scans?.size} access points")
        } else {
            say("  VERDICT: nothing here locates the device")
        }
    }
}
