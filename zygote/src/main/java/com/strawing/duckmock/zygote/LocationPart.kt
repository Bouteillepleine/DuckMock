package com.strawing.duckmock.zygote

import android.location.Location
import android.os.Bundle
import com.strawing.duckmock.common.Config
import com.strawing.duckmock.zygote.hook.Frame
import com.strawing.duckmock.zygote.hook.XHook
import com.strawing.duckmock.zygote.util.Logx
import com.strawing.duckmock.zygote.util.ModuleConfig

object LocationPart {

    @Volatile
    var hookCount = 0
        private set

    @Volatile
    var selfTestPassed: Boolean? = null
        private set

    @Volatile
    var cleared = 0
        private set

    @Volatile
    private var seen = false

    @Volatile
    private var selfTesting = false

    private val markNames = LinkedHashSet<String>()

    fun arm(): Int {
        val clazz = XHook.findClass(Config.LOCATION_CLASS) ?: run {
            Logx.e("android.location.Location not found")
            return 0
        }
        var count = 0
        for (name in Config.LOCATION_MARK_METHODS) {
            for (m in XHook.methodsOf(clazz)) {
                if (m.name != name) continue
                if (m.parameterCount != 1) continue
                if (m.parameterTypes[0] != Boolean::class.javaPrimitiveType) continue
                if (XHook.hook(m, ::onMark)) {
                    markNames.add(name)
                    count++
                }
            }
        }
        if (count == 0) {
            Logx.e("no mock marker found on Location, tried ${Config.LOCATION_MARK_METHODS}")
        } else {
            Logx.i("location marker neutralised: $markNames")
        }
        hookCount = count
        if (count > 0) selfTest()
        return count
    }

    private fun setter(): java.lang.reflect.Method? {
        for (name in markNames) {
            val m = runCatching {
                Location::class.java.getDeclaredMethod(name, Boolean::class.javaPrimitiveType)
            }.getOrNull()
            if (m != null) return m.apply { isAccessible = true }
        }
        return null
    }

    private fun getter(): java.lang.reflect.Method? {
        for (name in arrayOf("isMock", "isFromMockProvider")) {
            val m = runCatching { Location::class.java.getDeclaredMethod(name) }.getOrNull()
            if (m != null) return m.apply { isAccessible = true }
        }
        return null
    }

    private fun selfTest() {
        selfTesting = true
        selfTestPassed = try {
            val set = setter()
            val get = getter()
            if (set == null || get == null) {
                Logx.e("self-test cannot run: setter=${set?.name} getter=${get?.name}")
                null
            } else {
                val probe = Location("gps")
                set.invoke(probe, true)
                val marked = get.invoke(probe) as? Boolean
                if (marked == null) null else !marked
            }
        } catch (t: Throwable) {
            Logx.e("location self-test threw", t)
            null
        } finally {
            selfTesting = false
        }
        Logx.i("location self-test: ${verdict()}")
    }

    fun verdict(): String = when (selfTestPassed) {
        true -> "live"
        false -> "dead"
        null -> "unknown"
    }

    private fun onMark(f: Frame) {
        if (!seen) {
            seen = true
            Logx.i("location marker hook live")
        }
        val config = ModuleConfig.config
        if (!ModuleConfig.active() || !config.hideLocationFlag) {
            f.proceed()
            return
        }
        val wanted = f.arg(0) as? Boolean
        if (wanted != true) {
            f.proceed()
            return
        }
        f.setArg(0, false)
        if (!selfTesting) {
            cleared++
            SystemServerPart.service?.note(Config.SYSTEM_UID, Config.RECORD_LOCATION)
            Logx.v { "cleared the mock marker on a ${providerOf(f.thisObject) ?: "?"} location" }
        }
        f.proceed()
        if (config.normalizeProvider) normalizeProvider(f.thisObject)
        stripExtra(f.thisObject)
    }

    private fun providerOf(target: Any?): String? =
        (target as? Location)?.let { runCatching { it.provider }.getOrNull() }

    private fun normalizeProvider(target: Any?) {
        val location = target as? Location ?: return
        val provider = runCatching { location.provider }.getOrNull() ?: return
        if (provider in Config.KNOWN_PROVIDERS) return
        runCatching { location.provider = "gps" }
            .onSuccess { Logx.v { "renamed the mock provider $provider to gps" } }
            .onFailure { Logx.e("could not rename the mock provider $provider", it) }
    }

    private fun stripExtra(target: Any?) {
        val location = target as? Location ?: return
        val extras: Bundle = runCatching { location.extras }.getOrNull() ?: return
        if (!extras.containsKey(Config.MOCK_EXTRA)) return
        runCatching {
            val copy = Bundle(extras)
            copy.putBoolean(Config.MOCK_EXTRA, false)
            location.extras = copy
        }.onFailure { Logx.e("could not rewrite the ${Config.MOCK_EXTRA} extra", it) }
    }
}
