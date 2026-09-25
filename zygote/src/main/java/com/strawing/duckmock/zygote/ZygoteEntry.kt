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
            if (pkg != Config.SYSTEM_SERVER_PACKAGE) return
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
            if (pkg != Config.SYSTEM_SERVER_PACKAGE) {
                Logx.e("injected into $pkg, which DuckMock never asked for")
                return
            }
            Logx.v { "system_server reached (${ZygoteLoader.getProcessName()})" }
            SystemServerPart.init()
        } catch (t: Throwable) {
            Logx.e("main failed", t)
        }
    }
}
