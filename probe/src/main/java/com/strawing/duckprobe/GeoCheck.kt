package com.strawing.duckprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import java.util.Locale

class GeoCheck : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val query = intent?.getStringExtra("q") ?: "10 Downing Street, London"
        val pending = goAsync()
        Thread {
            try {
                run(context, query)
            } finally {
                pending.finish()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun run(context: Context, query: String) {
        fun say(line: String) = Log.i("DuckProbe", line)
        say("GEOCODER — is address lookup usable?")
        val present = runCatching { Geocoder.isPresent() }.getOrDefault(false)
        say("  isPresent = $present")
        if (!present) return

        val geocoder = Geocoder(context, Locale.getDefault())
        say("  query     = $query")

        val results = ArrayList<Address>()
        var error: String? = null
        val done = java.util.concurrent.CountDownLatch(1)

        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                geocoder.getFromLocationName(query, 5, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        results.addAll(addresses)
                        done.countDown()
                    }

                    override fun onError(message: String?) {
                        error = message
                        done.countDown()
                    }
                })
            }.onFailure { error = it.message; done.countDown() }
            done.await(15, java.util.concurrent.TimeUnit.SECONDS)
        } else {
            @Suppress("DEPRECATION")
            val r = runCatching { geocoder.getFromLocationName(query, 5) }
            r.getOrNull()?.let { results.addAll(it) }
            error = r.exceptionOrNull()?.message
        }

        say("  error     = ${error ?: "none"}")
        say("  hits      = ${results.size}")
        for (a in results.take(3)) {
            say("    ${a.getAddressLine(0)}  ->  ${a.latitude}, ${a.longitude}")
        }
    }
}
