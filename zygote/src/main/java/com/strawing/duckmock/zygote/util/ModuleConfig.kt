package com.strawing.duckmock.zygote.util

import com.strawing.duckmock.common.MockConfig
import java.io.File

object ModuleConfig {

    private const val CONFIG_NAME = "config.json"
    private const val DISABLE_NAME = "disable_hooks"

    @Volatile
    var config: MockConfig = MockConfig()

    @Volatile
    var disabled = false
        private set

    fun load(moduleDir: String?) {
        if (moduleDir == null) return
        disabled = runCatching { File(moduleDir, DISABLE_NAME).exists() }.getOrDefault(false)
        val text = runCatching { File(moduleDir, CONFIG_NAME).readText() }.getOrNull()
        config = MockConfig.parse(text)
        Logx.verbose = config.verboseLog
    }

    fun active(): Boolean = !disabled && !config.paused
}
