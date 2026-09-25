package com.strawing.duckmock.zygote

import android.os.SystemClock
import com.strawing.duckmock.zygote.service.MockService
import com.strawing.duckmock.zygote.util.Logx
import com.strawing.duckmock.zygote.util.ModuleConfig
import kotlin.concurrent.thread

object SystemServerPart {

    private const val SETTLE_MS = 5000L
    private const val BOOT_WAIT_PASSES = 600

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

    private fun armAfterBoot() {
        if (!waitForBootCompleted()) {
            Logx.e("boot never completed, nothing armed")
            return
        }
        Thread.sleep(SETTLE_MS)
        val config = ModuleConfig.config

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
            "armed: location=$location (${LocationPart.verdict()}) " +
                "settings=$settings appops=$appOps"
        )
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
