package com.strawing.duckmock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlin.math.cos
import kotlin.random.Random

class SpoofService : Service() {

    companion object {
        const val ACTION_START = "com.strawing.duckmock.SPOOF_START"
        const val ACTION_STOP = "com.strawing.duckmock.SPOOF_STOP"

        private const val CHANNEL = "position"
        private const val NOTIFICATION_ID = 42
        private const val TICK_MS = 1000L
        private const val DEGREES_PER_METRE = 1.0 / 111_320.0

        val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )

        fun start(context: Context) {
            val intent = Intent(context, SpoofService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SpoofService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }

    private var ticker: Thread? = null

    @Volatile
    private var running = false

    private val held = ArrayList<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutDown()
                return START_NOT_STICKY
            }
            else -> startUp()
        }
        return START_STICKY
    }

    private fun startUp() {
        if (running) return
        startForeground(NOTIFICATION_ID, notification())
        val claimed = SpoofConfig.claim(this)
        if (!claimed.ok && claimed.message.isNotEmpty()) {
            Log.e("DuckMock", "config claim: ${claimed.message}")
        }
        if (!claimProviders()) {
            Log.e("DuckMock", "could not take the location providers")
            shutDown()
            return
        }
        running = true
        SpoofPrefs.setRunning(this, true)
        ticker = Thread {
            while (running) {
                push()
                Thread.sleep(TICK_MS)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun shutDown() {
        running = false
        ticker = null
        releaseProviders()
        SpoofPrefs.setRunning(this, false)
        SpoofConfig.release(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        releaseProviders()
        SpoofPrefs.setRunning(this, false)
        super.onDestroy()
    }

    private fun locationManager(): LocationManager? =
        getSystemService(LocationManager::class.java)

    private fun claimProviders(): Boolean {
        val lm = locationManager() ?: return false
        held.clear()
        for (provider in PROVIDERS) {
            runCatching { lm.removeTestProvider(provider) }
            val ok = runCatching {
                lm.addTestProvider(
                    provider,
                    false, false, false, false, true, true, true,
                    1,
                    1,
                )
                lm.setTestProviderEnabled(provider, true)
            }
            if (ok.isSuccess) {
                held.add(provider)
            } else {
                Log.e("DuckMock", "provider $provider refused: ${ok.exceptionOrNull()?.message}")
            }
        }
        return held.isNotEmpty()
    }

    private fun releaseProviders() {
        val lm = locationManager() ?: return
        for (provider in held) runCatching { lm.removeTestProvider(provider) }
        held.clear()
    }

    private fun push() {
        val lm = locationManager() ?: return
        val target = SpoofPrefs.target(this)
        for (provider in held) {
            runCatching { lm.setTestProviderLocation(provider, build(provider, target)) }
                .onFailure { Log.e("DuckMock", "could not set $provider: ${it.message}") }
        }
    }

    private fun build(provider: String, target: SpoofTarget): Location {
        val jitter = target.jitterMetres.toDouble()
        val dLat = if (jitter <= 0.0) 0.0 else Random.nextDouble(-jitter, jitter) * DEGREES_PER_METRE
        val dLon = if (jitter <= 0.0) 0.0 else {
            val shrink = cos(Math.toRadians(target.latitude)).coerceAtLeast(0.01)
            Random.nextDouble(-jitter, jitter) * DEGREES_PER_METRE / shrink
        }
        return Location(provider).apply {
            latitude = target.latitude + dLat
            longitude = target.longitude + dLon
            altitude = target.altitude
            accuracy = target.accuracy
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            bearing = 0f
            speed = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = 3f
                speedAccuracyMetersPerSecond = 0.5f
                bearingAccuracyDegrees = 5f
            }
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Position", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val target = SpoofPrefs.target(this)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Position")
            .setContentText(target.label.ifBlank { target.pretty() })
            .setSmallIcon(R.drawable.ic_status)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }
}
