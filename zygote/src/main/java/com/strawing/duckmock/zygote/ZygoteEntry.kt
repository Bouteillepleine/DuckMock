package com.strawing.duckmock.zygote

import com.strawing.duckmock.common.Config
import com.strawing.duckmock.zygote.util.Logx
import com.strawing.duckmock.zygote.util.ModuleConfig
import com.strawing.duckmock.zygote.util.NativeLib
import com.v7878.zygisk.ZygoteLoader

object ZygoteEntry {

    @JvmStatic
    fun premain() {
        try {
            val pkg = ZygoteLoader.getPackageName()
            if (pkg != Config.SYSTEM_SERVER_PACKAGE && pkg != Config.PHONE_PACKAGE) return
            val moduleDir = ZygoteLoader.getModuleDir()
            ModuleConfig.load(moduleDir)
            NativeLib.load(moduleDir)
        } catch (t: Throwable) {
            Logx.e("premain failed", t)
        }
    }

    @JvmStatic
    fun main() {
        try {
            val pkg = ZygoteLoader.getPackageName()
            when (pkg) {
                Config.SYSTEM_SERVER_PACKAGE -> {
                    Logx.v { "system_server reached (${ZygoteLoader.getProcessName()})" }
                    SystemServerPart.init()
                }
                Config.PHONE_PACKAGE -> {
                    Logx.i("phone process reached (${ZygoteLoader.getProcessName()})")
                    PhonePart.init()
                }
                else -> Logx.e("injected into $pkg, which DuckMock never asked for")
            }
        } catch (t: Throwable) {
            Logx.e("main failed", t)
        }
    }
}
