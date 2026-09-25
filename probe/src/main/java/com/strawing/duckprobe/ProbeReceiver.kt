package com.strawing.duckprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        for (line in Probe.run(context)) {
            Log.i("DuckProbe", line)
        }
    }
}
