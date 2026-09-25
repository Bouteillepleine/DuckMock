package com.strawing.duckprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log

class MockAttempt : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        for (line in run(context)) Log.i("DuckProbe", line)
    }

    private fun run(context: Context): List<String> {
        val out = ArrayList<String>()
        out.add("MOCK ATTEMPT — can this app become a mock provider?")
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm == null) {
            out.add("  no LocationManager")
            return out
        }

        runCatching { lm.removeTestProvider(PROVIDER) }

        val added = runCatching {
            lm.addTestProvider(
                PROVIDER,
                false, false, false, false, true, true, true,
                1,
                1,
            )
        }
        if (added.isFailure) {
            val e = added.exceptionOrNull()
            out.add("  addTestProvider  = REFUSED (${e?.javaClass?.simpleName}: ${e?.message})")
            return out
        }
        out.add("  addTestProvider  = ACCEPTED")

        val enabled = runCatching { lm.setTestProviderEnabled(PROVIDER, true) }
        out.add("  setEnabled       = ${if (enabled.isSuccess) "ok" else "failed"}")

        val location = Location(PROVIDER).apply {
            latitude = 48.8566
            longitude = 2.3522
            accuracy = 10f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        val pushed = runCatching { lm.setTestProviderLocation(PROVIDER, location) }
        out.add("  setLocation      = ${if (pushed.isSuccess) "ok" else "failed"}")

        val back = runCatching { lm.getLastKnownLocation(PROVIDER) }.getOrNull()
        out.add("  read back        = ${back?.let { Probe.describeLocation(it) } ?: "null"}")

        runCatching { lm.removeTestProvider(PROVIDER) }
        out.add("  cleaned up")
        return out
    }

    private companion object {
        const val PROVIDER = LocationManager.GPS_PROVIDER
    }
}
