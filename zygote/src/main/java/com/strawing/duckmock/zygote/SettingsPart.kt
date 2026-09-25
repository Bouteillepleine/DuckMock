package com.strawing.duckmock.zygote

import android.content.ContentProvider
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.strawing.duckmock.common.Bridge
import com.strawing.duckmock.common.Config
import com.strawing.duckmock.zygote.hook.BridgeRegistry
import com.strawing.duckmock.zygote.hook.Frame
import com.strawing.duckmock.zygote.hook.XHook
import com.strawing.duckmock.zygote.util.CursorSpoof
import com.strawing.duckmock.zygote.util.Logx
import com.strawing.duckmock.zygote.util.ModuleConfig

object SettingsPart {

    @Volatile
    var hookCount = 0
        private set

    @Volatile
    var providerFound = false
        private set

    @Volatile
    var spoofed = 0
        private set

    @Volatile
    private var callSeen = false

    @Volatile
    private var querySeen = false

    fun arm(): Int {
        val provider = runCatching { localProvider() }.getOrNull() as? ContentProvider ?: run {
            Logx.e("settings provider not found, the settings key stays visible")
            return 0
        }
        providerFound = true
        val context: Context? = runCatching { provider.context }.getOrNull()
        if (context == null) {
            Logx.e("settings provider has no context")
            return 0
        }
        Targets.adopt(context)
        var count = 0
        for (m in XHook.methodsOf(provider.javaClass)) {
            when {
                m.name == "call" && m.parameterCount >= 3 -> if (XHook.hook(m, ::onCall)) count++
                m.name == "query" && m.parameterCount >= 3 -> if (XHook.hook(m, ::onQuery)) count++
            }
        }
        hookCount = count
        Logx.i("settings gate armed: $count methods on ${provider.javaClass.name}")
        return count
    }

    private fun localProvider(): Any? {
        val thread = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null) ?: return null
        val field = thread.javaClass.getDeclaredField("mLocalProvidersByName")
            .apply { isAccessible = true }
        val map = field.get(thread) as? Map<*, *> ?: return null
        for (record in map.values) {
            if (record == null) continue
            val local = runCatching {
                record.javaClass.getDeclaredField("mLocalProvider")
                    .apply { isAccessible = true }
                    .get(record)
            }.getOrNull() ?: continue
            val names = runCatching {
                record.javaClass.getDeclaredField("mNames")
                    .apply { isAccessible = true }
                    .get(record) as? Array<*>
            }.getOrNull()
            if (names?.any { it == Config.SETTINGS_AUTHORITY } == true ||
                local.javaClass.name == Config.SETTINGS_PROVIDER
            ) {
                return local
            }
        }
        return null
    }

    private fun hiding(): Boolean =
        ModuleConfig.active() && ModuleConfig.config.hideSettingsKey

    private fun matchesBridge(args: List<Any?>): Boolean {
        for (i in 0 until args.size - 1) {
            if (args[i] == Bridge.METHOD && args[i + 1] == Bridge.ARG) return true
        }
        return false
    }

    fun bridgeBundle(uid: Int?): Bundle? {
        val svc = SystemServerPart.service ?: return null
        if (uid == null || svc.callerAppId < 0) return null
        if (uid % Config.PER_USER_RANGE != svc.callerAppId) return null
        return Bundle().apply { putBinder(Bridge.KEY_BINDER, svc) }
    }

    val bridgeHandler = java.lang.reflect.InvocationHandler { _, _, args ->
        bridgeBundle(args?.getOrNull(0) as? Int)
    }

    private fun onCall(f: Frame) {
        if (!callSeen) {
            callSeen = true
            Logx.i("settings call hook live")
        }
        val caller = Targets.callingUid()
        if (caller != null && matchesBridge(f.args)) {
            val answer = bridgeBundle(caller)
            if (answer != null) {
                f.result = answer
                return
            }
        }
        if (caller != null) {
            val foreign = BridgeRegistry.serve(f.args, caller)
            if (foreign != null) {
                f.result = foreign
                return
            }
        }
        f.proceed()
        try {
            if (!hiding()) return
            val uid = caller ?: return
            val args = f.args
            var key: String? = null
            for (i in 0 until args.size - 1) {
                val a = args[i]
                if (a is String && a in Config.GET_METHODS) {
                    key = args[i + 1] as? String
                    break
                }
            }
            if (key != Config.MOCK_LOCATION_KEY) return
            if (!Targets.lieTo(uid)) return
            val bundle = f.result as? Bundle ?: return
            if (!bundle.containsKey(Config.CALL_VALUE)) return
            bundle.putString(Config.CALL_VALUE, "0")
            bundle.putInt(Config.CALL_GENERATION_INDEX, -1)
            spoofed++
            SystemServerPart.service?.note(uid, Config.RECORD_SETTINGS)
            Logx.v { "told ${Targets.describe(uid)} that ${Config.MOCK_LOCATION_KEY} is 0" }
        } catch (t: Throwable) {
            Logx.e("settings call spoof failed", t)
        }
    }

    private fun onQuery(f: Frame) {
        if (!querySeen) {
            querySeen = true
            Logx.i("settings query hook live")
        }
        f.proceed()
        try {
            if (!hiding() || !ModuleConfig.config.coverQueryPath) return
            val uid = Targets.callingUid() ?: return
            if (!Targets.lieTo(uid)) return
            val cursor = f.result as? Cursor ?: return
            val uri = f.args.firstOrNull { it is Uri } as? Uri
            val replaced = CursorSpoof.rewrite(cursor, uri?.lastPathSegment) ?: return
            f.result = replaced
            spoofed++
            SystemServerPart.service?.note(uid, Config.RECORD_QUERY)
            Logx.v { "rewrote a settings cursor for ${Targets.describe(uid)}" }
        } catch (t: Throwable) {
            Logx.e("settings query spoof failed", t)
        }
    }
}
