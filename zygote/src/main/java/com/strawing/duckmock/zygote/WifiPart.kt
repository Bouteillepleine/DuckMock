package com.strawing.duckmock.zygote

import com.strawing.duckmock.common.Config
import com.strawing.duckmock.zygote.hook.Frame
import com.strawing.duckmock.zygote.hook.XHook
import com.strawing.duckmock.zygote.util.Logx
import com.strawing.duckmock.zygote.util.ModuleConfig

object WifiPart {

    private const val REDACTED_BSSID = "02:00:00:00:00:00"
    private const val UNKNOWN_SSID = "<unknown ssid>"

    @Volatile
    var hookCount = 0
        private set

    @Volatile
    var redacted = 0
        private set

    @Volatile
    var note = "not armed"
        private set

    @Volatile
    var candidates: List<String> = emptyList()
        private set

    fun arm(): Int {
        val service = runCatching {
            Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
                .invoke(null, "wifi")
        }.getOrNull()
        if (service == null) {
            note = "no wifi service in this process"
            Logx.e(note)
            return 0
        }
        var count = 0
        val taken = ArrayList<String>()
        for (m in XHook.methodsOf(service.javaClass)) {
            val hooked = when (m.name) {
                "getScanResults", "getCachedScanData" -> XHook.hook(m, ::onScanResults)
                "getConnectionInfo" ->
                    m.returnType.name == "android.net.wifi.WifiInfo" &&
                        XHook.hook(m, ::onConnectionInfo)
                else -> false
            }
            if (hooked) {
                count++
                taken.add(m.name)
            }
        }
        hookCount = count
        note = if (count > 0) taken.distinct().joinToString(",") else "nothing hooked"
        candidates = XHook.methodsOf(service.javaClass)
            .map { it.name }
            .filter { it.contains("can", true) || it.contains("ConnectionInfo") }
            .distinct()
            .sorted()
        Logx.i("wifi service ${service.javaClass.name} exposes: $candidates")
        Logx.i("wifi gate armed: $note")
        return count
    }

    private fun lying(): Boolean {
        if (!ModuleConfig.active()) return false
        if (!ModuleConfig.config.hideWifi) return false
        if (!SpoofState.active) return false
        val uid = Targets.callingUid() ?: return false
        return Targets.lieTo(uid)
    }

    private fun onScanResults(f: Frame) {
        f.proceed()
        if (!lying()) return
        val original = f.result ?: return
        val emptied = emptyLike(original) ?: run {
            Logx.e("cannot empty a ${original.javaClass.name} from ${f.member.name}")
            return
        }
        f.result = emptied
        redacted++
        SystemServerPart.service?.note(Targets.callingUid() ?: 0, Config.RECORD_WIFI)
        Logx.v { "withheld the scan list from ${Targets.describe(Targets.callingUid() ?: 0)}" }
    }

    private fun emptyLike(original: Any): Any? {
        if (original is List<*>) {
            return if (original.isEmpty()) null else emptyList<Any>()
        }
        val cls = original.javaClass
        val getList = runCatching { cls.getMethod("getList") }.getOrNull() ?: return null
        val size = runCatching { (getList.invoke(original) as? List<*>)?.size }.getOrNull() ?: 0
        if (size == 0) return null
        runCatching { cls.getDeclaredConstructor(List::class.java).apply { isAccessible = true } }
            .getOrNull()
            ?.let { ctor -> runCatching { ctor.newInstance(emptyList<Any>()) }.getOrNull() }
            ?.let { return it }
        return runCatching {
            cls.getDeclaredMethod("emptyList").apply { isAccessible = true }.invoke(null)
        }.getOrNull()
    }

    private fun onConnectionInfo(f: Frame) {
        f.proceed()
        if (!lying()) return
        val original = f.result ?: return
        val redactedInfo = redact(original) ?: return
        f.result = redactedInfo
        redacted++
        SystemServerPart.service?.note(Targets.callingUid() ?: 0, Config.RECORD_WIFI)
        Logx.v { "redacted the connected access point for ${Targets.describe(Targets.callingUid() ?: 0)}" }
    }

    private fun redact(source: Any): Any? {
        copyAndBlank(source)?.let { return it }
        return buildBlank(source)
    }

    private fun copyAndBlank(source: Any): Any? = runCatching {
        val cls = source.javaClass
        val copy = cls.getDeclaredConstructor(cls).apply { isAccessible = true }.newInstance(source)
        val setBssid = cls.getDeclaredMethod("setBSSID", String::class.java).apply { isAccessible = true }
        setBssid.invoke(copy, REDACTED_BSSID)
        val setSsid = cls.declaredMethods.firstOrNull {
            it.name == "setSSID" && it.parameterCount == 1
        }
        if (setSsid != null) {
            setSsid.isAccessible = true
            val type = setSsid.parameterTypes[0]
            val value: Any? = when {
                type == String::class.java -> UNKNOWN_SSID
                type.name == "android.net.wifi.WifiSsid" -> runCatching {
                    type.getDeclaredMethod("fromBytes", ByteArray::class.java)
                        .invoke(null, UNKNOWN_SSID.toByteArray())
                }.getOrNull()
                else -> null
            }
            if (value != null) setSsid.invoke(copy, value)
        }
        copy
    }.getOrNull()

    private fun buildBlank(source: Any): Any? = runCatching {
        val builderClass = Class.forName("android.net.wifi.WifiInfo\$Builder")
        val builder = builderClass.getDeclaredConstructor().newInstance()
        builderClass.getMethod("setBssid", String::class.java).invoke(builder, REDACTED_BSSID)
        builderClass.getMethod("setSsid", ByteArray::class.java)
            .invoke(builder, UNKNOWN_SSID.toByteArray())
        runCatching {
            val rssi = source.javaClass.getMethod("getRssi").invoke(source) as? Int
            if (rssi != null) builderClass.getMethod("setRssi", Int::class.javaPrimitiveType)
                .invoke(builder, rssi)
        }
        builderClass.getMethod("build").invoke(builder)
    }.getOrNull()
}
