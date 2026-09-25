package com.strawing.duckmock.zygote

import com.strawing.duckmock.zygote.util.Logx
import com.strawing.duckmock.zygote.util.ModuleConfig
import java.lang.reflect.Method
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.random.Random

object GnssPart {

    private const val TICK_MS = 1000L
    private const val WINDOW_MS = 30_000L

    private class Sat(
        val constellation: Int,
        val svid: Int,
        val cn0: Float,
        val elevation: Float,
        val azimuth: Float,
        val usedInFix: Boolean,
    )

    @Volatile
    private var provider: Any? = null

    @Volatile
    private var report: Method? = null

    @Volatile
    private var builderClass: Class<*>? = null

    @Volatile
    var resolution = "not tried"
        private set

    @Volatile
    private var spoofing = false

    @Volatile
    private var latitude = 0.0

    @Volatile
    private var longitude = 0.0

    @Volatile
    var pushed = 0
        private set

    @Volatile
    private var ticker: Thread? = null

    fun setSpoofing(on: Boolean, lat: Double, lon: Double) {
        latitude = lat
        longitude = lon
        if (on == spoofing) return
        spoofing = on
        if (on) start() else Logx.i("synthetic gnss stopping")
    }

    private fun start() {
        if (!ModuleConfig.config.synthesiseGnss) {
            Logx.i("synthetic gnss is switched off")
            return
        }
        if (!resolve()) {
            Logx.e("synthetic gnss cannot run: $resolution")
            return
        }
        if (ticker?.isAlive == true) return
        ticker = thread(name = "duckmock-gnss", isDaemon = true) {
            Logx.i("synthetic gnss started")
            while (spoofing && ModuleConfig.active() && ModuleConfig.config.synthesiseGnss) {
                runCatching { pushOnce() }
                    .onFailure { Logx.e("synthetic gnss push failed", it) }
                Thread.sleep(TICK_MS)
            }
            Logx.i("synthetic gnss stopped after $pushed reports")
        }
    }

    fun resolve(): Boolean {
        if (report != null && provider != null) return true
        val service = runCatching {
            Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
                .invoke(null, "location")
        }.getOrNull() ?: run {
            resolution = "no location service"
            return false
        }
        val gnssManager = fieldWhoseTypeContains(service, "GnssManagerService") ?: run {
            resolution = "no GnssManagerService inside ${service.javaClass.simpleName}"
            return false
        }
        val statusProvider = fieldWhoseTypeContains(gnssManager, "GnssStatusProvider") ?: run {
            resolution = "no GnssStatusProvider inside ${gnssManager.javaClass.simpleName}"
            return false
        }
        val method = methodTakingGnssStatus(statusProvider.javaClass) ?: run {
            resolution = "no report method on ${statusProvider.javaClass.name}"
            return false
        }
        val builder = runCatching { Class.forName("android.location.GnssStatus\$Builder") }.getOrNull()
            ?: run {
                resolution = "no GnssStatus.Builder on this platform"
                return false
            }
        provider = statusProvider
        report = method
        builderClass = builder
        resolution = "${statusProvider.javaClass.simpleName}.${method.name}"
        Logx.i("synthetic gnss resolved: $resolution")
        return true
    }

    private fun fieldWhoseTypeContains(target: Any, marker: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            for (f in cls.declaredFields) {
                if (!f.type.name.contains(marker)) continue
                val value = runCatching { f.apply { isAccessible = true }.get(target) }.getOrNull()
                if (value != null) return value
            }
            cls = cls.superclass
        }
        return null
    }

    private fun methodTakingGnssStatus(start: Class<*>): Method? {
        var cls: Class<*>? = start
        while (cls != null) {
            for (m in cls.declaredMethods) {
                if (m.parameterCount != 1) continue
                if (m.parameterTypes[0].name != "android.location.GnssStatus") continue
                return m.apply { isAccessible = true }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun pushOnce() {
        val target = provider ?: return
        val method = report ?: return
        val status = build(satellites()) ?: return
        method.invoke(target, status)
        pushed++
        Logx.v { "pushed a synthetic sky of satellites" }
    }

    private fun build(sats: List<Sat>): Any? {
        val cls = builderClass ?: return null
        val builder = runCatching { cls.getDeclaredConstructor().newInstance() }.getOrNull() ?: return null
        val add = cls.declaredMethods.firstOrNull { it.name == "addSatellite" } ?: return null
        add.isAccessible = true
        for (sat in sats) {
            val args = arguments(add, sat)
            runCatching { add.invoke(builder, *args) }
                .onFailure { Logx.e("addSatellite refused", it); return null }
        }
        val build = runCatching { cls.getDeclaredMethod("build") }.getOrNull() ?: return null
        build.isAccessible = true
        return runCatching { build.invoke(builder) }.getOrNull()
    }

    private fun arguments(add: Method, sat: Sat): Array<Any?> {
        val types = add.parameterTypes
        val out = arrayOfNulls<Any?>(types.size)
        var ints = 0
        var floats = 0
        var bools = 0
        for (i in types.indices) {
            out[i] = when (types[i]) {
                Int::class.javaPrimitiveType -> when (ints++) {
                    0 -> sat.constellation
                    else -> sat.svid
                }
                Float::class.javaPrimitiveType -> when (floats++) {
                    0 -> sat.cn0
                    1 -> sat.elevation
                    2 -> sat.azimuth
                    3 -> 1575420000f
                    else -> sat.cn0 - 8f
                }
                Boolean::class.javaPrimitiveType -> when (bools++) {
                    0 -> sat.usedInFix
                    1 -> sat.usedInFix
                    2 -> sat.usedInFix
                    3 -> true
                    else -> false
                }
                else -> null
            }
        }
        return out
    }

    private fun satellites(): List<Sat> {
        val window = System.currentTimeMillis() / WINDOW_MS
        val seed = (abs(latitude * 1000).toLong() * 31 + abs(longitude * 1000).toLong()) * 97 + window
        val random = Random(seed)
        val drift = (System.currentTimeMillis() % WINDOW_MS) / WINDOW_MS.toFloat()

        val plan = listOf(
            CONSTELLATION_GPS to random.nextInt(6, 10),
            CONSTELLATION_GLONASS to random.nextInt(3, 6),
            CONSTELLATION_GALILEO to random.nextInt(3, 6),
            CONSTELLATION_BEIDOU to random.nextInt(2, 5),
        )

        val out = ArrayList<Sat>()
        var used = 0
        for ((constellation, count) in plan) {
            val taken = HashSet<Int>()
            repeat(count) {
                var svid = random.nextInt(1, svidCeiling(constellation))
                while (!taken.add(svid)) svid = random.nextInt(1, svidCeiling(constellation))
                val elevation = random.nextInt(5, 86).toFloat()
                val azimuth = (random.nextInt(0, 360) + drift * 4f) % 360f
                val cn0 = when {
                    elevation > 55 -> random.nextInt(34, 47)
                    elevation > 25 -> random.nextInt(26, 39)
                    else -> random.nextInt(17, 29)
                }.toFloat()
                val useful = elevation > 15f && cn0 > 24f && used < 11
                if (useful) used++
                out.add(Sat(constellation, svid, cn0, elevation, azimuth, useful))
            }
        }
        return out
    }

    private fun svidCeiling(constellation: Int): Int = when (constellation) {
        CONSTELLATION_GPS -> 33
        CONSTELLATION_GLONASS -> 25
        CONSTELLATION_GALILEO -> 37
        else -> 38
    }

    private const val CONSTELLATION_GPS = 1
    private const val CONSTELLATION_GLONASS = 3
    private const val CONSTELLATION_BEIDOU = 5
    private const val CONSTELLATION_GALILEO = 6
}
