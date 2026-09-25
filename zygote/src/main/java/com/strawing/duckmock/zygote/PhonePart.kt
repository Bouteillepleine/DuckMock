package com.strawing.duckmock.zygote

import com.strawing.duckmock.zygote.hook.Frame
import com.strawing.duckmock.zygote.hook.XHook
import com.strawing.duckmock.zygote.util.Logx
import com.strawing.duckmock.zygote.util.ModuleConfig
import kotlin.concurrent.thread

object PhonePart {

    private const val SETTLE_MS = 25_000L

    private val CELL_METHODS = setOf(
        "getAllCellInfo",
        "getCellLocation",
        "requestCellInfoUpdate",
        "requestCellInfoUpdateWithWorkSource",
    )

    @Volatile
    var hookCount = 0
        private set

    @Volatile
    var withheld = 0
        private set

    fun init() {
        if (ModuleConfig.disabled) {
            Logx.i("kill switch present, the phone process is left alone")
            return
        }
        if (!ModuleConfig.config.hideCells) {
            Logx.i("cell hiding is switched off, the phone process is left alone")
            return
        }
        thread(name = "duckmock-phone", isDaemon = true) {
            Thread.sleep(SETTLE_MS)
            runCatching { arm() }.onFailure { Logx.e("phone gate failed to arm", it) }
        }
    }

    private fun arm() {
        val service = runCatching {
            Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
                .invoke(null, "phone")
        }.getOrNull()
        if (service == null) {
            Logx.e("no phone service in this process")
            return
        }
        var count = 0
        val taken = ArrayList<String>()
        for (m in XHook.methodsOf(service.javaClass)) {
            if (m.name !in CELL_METHODS) continue
            if (XHook.hook(m, ::onCellRequest)) {
                count++
                taken.add(m.name)
            }
        }
        hookCount = count
        Logx.i("phone gate armed on ${service.javaClass.name}: $count methods $taken")
    }

    private fun withholding(): Boolean {
        if (!ModuleConfig.active()) return false
        if (!ModuleConfig.config.hideCells) return false
        val uid = Targets.callingUid() ?: return false
        return Targets.lieTo(uid)
    }

    private fun onCellRequest(f: Frame) {
        if (!withholding()) {
            f.proceed()
            return
        }
        val returns = f.returnType
        when {
            returns == Void.TYPE -> Unit
            List::class.java.isAssignableFrom(returns) -> f.result = emptyList<Any>()
            returns.isPrimitive -> {
                f.proceed()
                return
            }
            else -> f.result = null
        }
        withheld++
        Logx.v { "withheld ${f.member.name} from ${Targets.describe(Targets.callingUid() ?: 0)}" }
    }
}
