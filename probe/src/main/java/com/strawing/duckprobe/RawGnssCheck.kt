package com.strawing.duckprobe

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.GnssNavigationMessage
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

class RawGnssCheck : BroadcastReceiver() {

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
        say("RAW GNSS — can this app get pseudoranges and navigation messages?")

        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            say("  no fine location permission")
            return
        }
        val lm = context.getSystemService(LocationManager::class.java) ?: run {
            say("  no LocationManager")
            return
        }

        val thread = HandlerThread("rawgnss").apply { start() }
        val handler = Handler(thread.looper)
        var measurementEvents = 0
        var navigationEvents = 0

        val measurements = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                measurementEvents++
            }
        }
        val navigation = object : GnssNavigationMessage.Callback() {
            override fun onGnssNavigationMessageReceived(event: GnssNavigationMessage) {
                navigationEvents++
            }
        }

        val measurementsOk = runCatching {
            lm.registerGnssMeasurementsCallback(measurements, handler)
        }.getOrDefault(false)
        val navigationOk = runCatching {
            lm.registerGnssNavigationMessageCallback(navigation, handler)
        }.getOrDefault(false)

        say("  measurements registered = $measurementsOk")
        say("  navigation registered   = $navigationOk")

        Thread.sleep(12_000)

        runCatching { lm.unregisterGnssMeasurementsCallback(measurements) }
        runCatching { lm.unregisterGnssNavigationMessageCallback(navigation) }
        thread.quitSafely()

        say("  measurement events in 12s = $measurementEvents")
        say("  navigation events in 12s  = $navigationEvents")
        if (!measurementsOk && !navigationOk) {
            say("  VERDICT: both raw streams refused")
        } else if (measurementEvents == 0 && navigationEvents == 0) {
            say("  VERDICT: accepted but nothing delivered")
        } else {
            say("  VERDICT: raw GNSS is flowing — it can contradict a faked sky")
        }
    }
}
