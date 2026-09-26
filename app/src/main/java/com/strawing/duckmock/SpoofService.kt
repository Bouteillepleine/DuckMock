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
import java.util.Locale
import kotlin.math.cos
import kotlin.random.Random

class SpoofService : Service() {

    companion object {
        const val ACTION_START = "com.strawing.duckmock.SPOOF_START"
        const val ACTION_STOP = "com.strawing.duckmock.SPOOF_STOP"
        const val ACTION_RETARGET = "com.strawing.duckmock.SPOOF_RETARGET"
        const val ACTION_JOY_SHOW = "com.strawing.duckmock.JOY_SHOW"
        const val ACTION_JOY_HIDE = "com.strawing.duckmock.JOY_HIDE"

        private const val CHANNEL = "position"
        private const val NOTIFICATION_ID = 42
        private const val TICK_IDLE_MS = 1000L
        private const val TICK_MOVING_MS = 400L
        private const val REPORT_EVERY_MS = 5_000L
        private const val DEGREES_PER_METRE = 1.0 / 111_320.0

        val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )

        fun start(context: Context) {
            val intent = Intent(context, SpoofService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun retarget(context: Context) {
            send(context, ACTION_RETARGET)
        }

        fun stop(context: Context) {
            send(context, ACTION_STOP)
        }

        fun showJoystick(context: Context) {
            send(context, ACTION_JOY_SHOW)
        }

        fun hideJoystick(context: Context) {
            send(context, ACTION_JOY_HIDE)
        }

        private fun send(context: Context, action: String) {
            val intent = Intent(context, SpoofService::class.java).setAction(action)
            runCatching { context.startService(intent) }
        }
    }

    private var ticker: Thread? = null

    @Volatile
    private var running = false

    private val held = ArrayList<String>()
    private val overlay by lazy { OverlayController(this) }

    @Volatile
    private var bearing = 0f

    @Volatile
    private var speed = 0f

    private var reportedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutDown()
                return START_NOT_STICKY
            }

            ACTION_RETARGET -> {
                if (!running) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                postNotification()
                return START_STICKY
            }

            ACTION_JOY_SHOW -> {
                if (!running) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!overlay.show()) Log.e("DuckMock", "the joystick was refused a window")
                return START_STICKY
            }

            ACTION_JOY_HIDE -> {
                if (!running) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                overlay.hide()
                return START_STICKY
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
        JoyState.release()
        bearing = 0f
        speed = 0f
        val target = SpoofPrefs.target(this)
        runCatching { ServiceClient.setSpoofing(this, true, target.latitude, target.longitude) }
        if (SpoofPrefs.joystick(this)) overlay.show()
        ticker = Thread {
            var last = SystemClock.elapsedRealtime()
            while (running) {
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - last) / 1000.0
                last = now
                val moving = advance(elapsed)
                push()
                report(now)
                overlay.refresh()
                Thread.sleep(if (moving) TICK_MOVING_MS else TICK_IDLE_MS)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun shutDown() {
        running = false
        ticker = null
        RouteState.stop()
        overlay.hide()
        releaseProviders()
        SpoofPrefs.setRunning(this, false)
        runCatching { ServiceClient.setSpoofing(this, false, 0.0, 0.0) }
        SpoofConfig.release(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        overlay.hide()
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

    private fun advance(elapsed: Double): Boolean {
        val target = SpoofPrefs.target(this)
        val pace = SpoofPrefs.pace(this).metresPerSecond
        val onRoute = RouteState.playing != null
        val step = RouteState.advance(pace, elapsed) ?: Walker.step(
            target.latitude,
            target.longitude,
            JoyState.east,
            JoyState.north,
            pace,
            elapsed,
        )
        if (onRoute && RouteState.playing == null) JoyState.release()
        if (!step.moved) {
            speed = 0f
            JoyState.speed = 0f
            RouteState.record(
                RoutePoint(target.latitude, target.longitude, target.altitude)
            )
            return false
        }
        bearing = step.bearing
        speed = step.speed
        JoyState.bearing = step.bearing
        JoyState.speed = step.speed
        val altitude = step.altitude ?: target.altitude
        SpoofPrefs.setTarget(
            this,
            target.copy(
                latitude = step.latitude,
                longitude = step.longitude,
                altitude = altitude,
                label = "",
            ),
        )
        RouteState.record(RoutePoint(step.latitude, step.longitude, altitude))
        return true
    }

    private fun report(now: Long) {
        if (now - reportedAt < REPORT_EVERY_MS) return
        reportedAt = now
        val target = SpoofPrefs.target(this)
        runCatching { ServiceClient.setSpoofing(this, true, target.latitude, target.longitude) }
        postNotification()
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
        val jitter = if (speed > 0f) 0.0 else target.jitterMetres.toDouble()
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
            bearing = this@SpoofService.bearing
            speed = this@SpoofService.speed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = 3f
                speedAccuracyMetersPerSecond = 0.5f
                bearingAccuracyDegrees = 5f
            }
        }
    }

    private fun postNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification())
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
        val detail = if (speed > 0f) {
            String.format(
                Locale.ROOT,
                "%s · %.1f m/s · %03.0f°",
                target.pretty(),
                speed,
                bearing,
            )
        } else {
            target.label.ifBlank { target.pretty() }
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Position")
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_status)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }
}
