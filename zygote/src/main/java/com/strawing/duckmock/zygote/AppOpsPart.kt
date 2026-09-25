package com.strawing.duckmock.zygote

import android.app.AppOpsManager
import com.strawing.duckmock.common.Config
import com.strawing.duckmock.zygote.hook.Frame
import com.strawing.duckmock.zygote.hook.XHook
import com.strawing.duckmock.zygote.util.Logx
import com.strawing.duckmock.zygote.util.ModuleConfig

object AppOpsPart {

    private val CHECK_METHODS = listOf("checkOperationImpl", "checkOperation")

    @Volatile
    var hookCount = 0
        private set

    @Volatile
    var opCode = Config.OP_MOCK_LOCATION_FALLBACK
        private set

    @Volatile
    var hidden = 0
        private set

    @Volatile
    var granted = 0
        private set

    @Volatile
    var grantHooks = 0
        private set

    @Volatile
    private var seen = false

    private val NOTE_METHODS = listOf(
        "noteOp", "noteOpNoThrow", "noteOperation", "noteOperationImpl",
        "checkOpNoThrow", "unsafeCheckOpNoThrow", "checkOp",
    )

    fun armGrant(): Int {
        val manager = XHook.findClass("android.app.AppOpsManager") ?: return 0
        var count = 0
        for (name in NOTE_METHODS) {
            count += XHook.hookAll(
                manager,
                name,
                minParams = 2,
                returnType = Int::class.javaPrimitiveType,
                body = ::onNote,
            )
        }
        grantHooks = count
        Logx.i("grant path armed: $count methods on AppOpsManager")
        return count
    }

    private fun isOurOp(value: Any?): Boolean = when (value) {
        is Int -> value == opCode
        is String -> value == Config.OPSTR_MOCK_LOCATION
        else -> false
    }

    private fun onNote(f: Frame) {
        if (!ModuleConfig.active() || !ModuleConfig.config.grantMockOp) {
            f.proceed()
            return
        }
        if (!isOurOp(f.arg(0))) {
            f.proceed()
            return
        }
        val uid = f.intArg(1)
        if (uid == null || !Targets.isSpoofer(uid)) {
            f.proceed()
            return
        }
        f.result = Config.MODE_ALLOWED
        granted++
        SystemServerPart.service?.note(uid, Config.RECORD_GRANT)
        Logx.v { "let ${Targets.describe(uid)} use the mock-location op via ${f.member.name}" }
    }

    fun arm(): Int {
        opCode = resolveOpCode()
        val loader = XHook.systemServerClassLoader()
        val clazz = XHook.findClass(Config.APP_OPS_SERVICE_CLASS, loader)
            ?: XHook.findClass(Config.APP_OPS_SERVICE_LEGACY_CLASS, loader)
            ?: run {
                Logx.e("AppOpsService not found, app-ops hiding not armed")
                return 0
            }
        var count = 0
        for (name in CHECK_METHODS) {
            count += XHook.hookAll(
                clazz,
                name,
                minParams = 2,
                returnType = Int::class.javaPrimitiveType,
                body = ::onCheck,
            )
        }
        hookCount = count
        if (count == 0) {
            Logx.e("no check method hooked on ${clazz.name}")
        } else {
            Logx.i("app-ops gate armed: $count methods on ${clazz.name}, op=$opCode")
        }
        return count
    }

    private fun resolveOpCode(): Int {
        val resolved = runCatching {
            AppOpsManager::class.java
                .getDeclaredMethod("strOpToOp", String::class.java)
                .apply { isAccessible = true }
                .invoke(null, Config.OPSTR_MOCK_LOCATION) as? Int
        }.getOrNull()
        if (resolved == null) {
            Logx.e("strOpToOp unavailable, falling back to op ${Config.OP_MOCK_LOCATION_FALLBACK}")
            return Config.OP_MOCK_LOCATION_FALLBACK
        }
        return resolved
    }

    private fun onCheck(f: Frame) {
        if (!seen) {
            seen = true
            Logx.i("app-ops gate hook live")
        }
        val code = f.intArg(0)
        if (code != opCode) {
            f.proceed()
            return
        }
        val config = ModuleConfig.config
        if (!ModuleConfig.active()) {
            f.proceed()
            return
        }
        val caller = Targets.callingUid()
        if (config.hideAppOps && caller != null && Targets.lieTo(caller)) {
            f.result = Config.MODE_ERRORED
            hidden++
            SystemServerPart.service?.note(caller, Config.RECORD_APPOPS)
            Logx.v { "told ${Targets.describe(caller)} that nothing holds the mock-location op" }
            return
        }
        val queried = f.intArg(1)
        if (config.grantMockOp && queried != null && Targets.isSpoofer(queried)) {
            f.result = Config.MODE_ALLOWED
            granted++
            SystemServerPart.service?.note(queried, Config.RECORD_GRANT)
            Logx.v { "granted the mock-location op to ${Targets.describe(queried)}" }
            return
        }
        f.proceed()
    }
}
