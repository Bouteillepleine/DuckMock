package com.strawing.duckmock.zygote

import android.os.SystemClock
import com.strawing.duckmock.common.Config
import com.strawing.duckmock.zygote.hook.InitLock
import com.strawing.duckmock.zygote.hook.XHook
import com.strawing.duckmock.zygote.service.MockService
import com.strawing.duckmock.zygote.util.Logx
import com.strawing.duckmock.zygote.util.ModuleConfig
import kotlin.concurrent.thread

object SystemServerPart {

    private const val SETTLE_MS = 20000L
    private const val BOOT_WAIT_PASSES = 600
    private const val MODULE_LIB_PREFIX = "/data/adb/modules/"

    @Volatile
    var service: MockService? = null
        private set

    fun init() {
        if (ModuleConfig.disabled) {
            Logx.i("kill switch present, no hooks installed")
            return
        }
        if (!ModuleConfig.config.anyHookWanted()) {
            Logx.i("every hook is switched off, nothing to arm")
            return
        }
        thread(name = "duckmock-arm", isDaemon = true) { armAfterBoot() }
    }

    fun coResidentModules(): List<String> = runCatching {
        java.io.File("/proc/self/maps").readLines()
            .mapNotNull { line ->
                val at = line.indexOf(MODULE_LIB_PREFIX)
                if (at < 0 || !line.endsWith(".so")) return@mapNotNull null
                line.substring(at + MODULE_LIB_PREFIX.length).substringBefore('/')
            }
            .filter { it.isNotEmpty() && it != Config.MODULE_ID }
            .distinct()
    }.getOrDefault(emptyList())

    private fun armAfterBoot() {
        if (!waitForBootCompleted()) {
            Logx.e("boot never completed, nothing armed")
            return
        }
        Thread.sleep(SETTLE_MS)

        val neighbours = coResidentModules()
        if (neighbours.isEmpty()) {
            Logx.i("no other module library is mapped into system_server")
        } else {
            Logx.e("other module libraries are mapped into system_server: $neighbours")
            Logx.e("each one carrying its own inline hooker will fight over the same ART code")
        }

        val config = ModuleConfig.config

        InitLock.serialized {
            val settings = runCatching { SettingsPart.arm() }
                .onFailure { Logx.e("settings gate failed to arm", it) }
                .getOrDefault(0)

            val context = Targets.systemContext()
            if (context != null) {
                val svc = MockService(context)
                svc.installedAtRealtimeMs = SystemClock.elapsedRealtime()
                service = svc
            } else {
                Logx.e("no system context, the manager app will not see live state")
            }

            val location = if (config.hideLocationFlag) {
                runCatching { LocationPart.arm() }
                    .onFailure { Logx.e("location gate failed to arm", it) }
                    .getOrDefault(0)
            } else 0

            val appOps = if (config.hideAppOps || config.grantMockOp) {
                runCatching { AppOpsPart.arm() }
                    .onFailure { Logx.e("app-ops gate failed to arm", it) }
                    .getOrDefault(0)
            } else 0

            Logx.i(
                "armed: engine=${XHook.engineMode()} location=$location " +
                    "(${LocationPart.verdict()}) settings=$settings appops=$appOps"
            )
        }
    }

    private fun waitForBootCompleted(): Boolean {
        val get = runCatching {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("get", String::class.java)
                .apply { isAccessible = true }
        }.getOrNull() ?: return false
        repeat(BOOT_WAIT_PASSES) {
            val value = runCatching { get.invoke(null, "sys.boot_completed") as? String }.getOrNull()
            if (value == "1") return true
            Thread.sleep(1000)
        }
        return false
    }
}
