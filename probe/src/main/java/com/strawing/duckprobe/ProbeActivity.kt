package com.strawing.duckprobe

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class ProbeActivity : AppCompatActivity(), LocationListener {

    private lateinit var output: TextView
    private lateinit var liveLine: TextView
    private var listening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad(), pad(), pad())
        }
        column.addView(TextView(this).apply {
            text = "DuckProbe"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
        })
        column.addView(Button(this).apply {
            text = "Run again"
            setOnClickListener { refresh() }
        })
        column.addView(Button(this).apply {
            text = "Watch live fixes"
            setOnClickListener { startListening() }
        })
        liveLine = TextView(this).apply {
            text = "live: not listening"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(Typeface.MONOSPACE)
        }
        column.addView(liveLine)
        output = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(Typeface.MONOSPACE)
            setTextIsSelectable(true)
        }
        column.addView(output)

        setContentView(ScrollView(this).apply {
            addView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                1,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        stopListening()
    }

    private fun refresh() {
        val lines = Probe.run(this)
        output.text = lines.joinToString("\n")
        for (line in lines) Log.i("DuckProbe", line)
    }

    private fun startListening() {
        if (listening) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            liveLine.text = "live: location permission not granted"
            return
        }
        val lm = getSystemService(LocationManager::class.java) ?: return
        val started = runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this)
            lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 0L, 0f, this)
        }.isSuccess
        listening = started
        liveLine.text = if (started) "live: waiting for a fix…" else "live: could not subscribe"
    }

    private fun stopListening() {
        if (!listening) return
        runCatching { getSystemService(LocationManager::class.java)?.removeUpdates(this) }
        listening = false
    }

    override fun onLocationChanged(location: Location) {
        val line = "live: ${Probe.describeLocation(location)}"
        liveLine.text = line
        Log.i("DuckProbe", line)
    }

    @Deprecated("kept for older platforms")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    private fun pad(): Int = (16 * resources.displayMetrics.density).toInt()
}
