package com.strawing.duckprobe

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

class GnssWatch : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        Thread {
            try {
                watch(context)
            } finally {
                pending.finish()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun watch(context: Context) {
        fun say(line: String) = Log.i("DuckProbe", line)

        say("GNSS WATCH — what the satellite view says")
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            say("  no fine location permission")
            return
        }
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm == null) {
            say("  no LocationManager")
            return
        }

        val thread = HandlerThread("gnss").apply { start() }
        val handler = Handler(thread.looper)
        var reports = 0
        var lastTotal = -1
        var lastUsed = -1
        var cn0Low = Float.MAX_VALUE
        var cn0High = 0f
        val constellations = LinkedHashSet<Int>()

        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                reports++
                lastTotal = status.satelliteCount
                var used = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) used++
                    val cn0 = status.getCn0DbHz(i)
                    if (cn0 < cn0Low) cn0Low = cn0
                    if (cn0 > cn0High) cn0High = cn0
                    constellations.add(status.getConstellationType(i))
                }
                lastUsed = used
            }
        }

        val locationListener = LocationListener { }
        val registered = runCatching {
            lm.registerGnssStatusCallback(callback, handler)
        }.getOrDefault(false)
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locationListener, thread.looper)
        }
        say("  callback registered = $registered")

        Thread.sleep(12_000)

        runCatching { lm.unregisterGnssStatusCallback(callback) }
        runCatching { lm.removeUpdates(locationListener) }
        thread.quitSafely()

        val fix = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        say("  gps fix present      = ${fix != null}")
        say("  status reports in 12s= $reports")
        say("  satellites seen      = $lastTotal")
        say("  used in fix          = $lastUsed")
        say("  constellations       = ${constellations.joinToString(",").ifEmpty { "none" }}")
        say("  cn0 range            = ${if (cn0High > 0f) "$cn0Low .. $cn0High" else "none"}")
        if (fix != null && lastUsed <= 0) {
            say("  VERDICT: a GPS fix with no satellites behind it — this is the GNSS tell")
        } else if (fix != null) {
            say("  VERDICT: fix backed by $lastUsed satellites")
        } else {
            say("  VERDICT: no fix to judge")
        }
    }
}
