package com.strawing.duckmock.zygote

import com.strawing.duckmock.zygote.util.Logx
import java.io.File
import kotlin.concurrent.thread

object BootGuard {

    private const val PATH = "/data/system/duckmock_arms"
    private const val MAX_FAILED_ARMS = 3
    private const val SURVIVED_MS = 120_000L

    @Volatile
    var attempts = 0
        private set

    fun read(): Int = runCatching {
        File(PATH).readText().trim().toIntOrNull() ?: 0
    }.getOrDefault(0)

    private fun write(value: Int) {
        runCatching { File(PATH).writeText("$value\n") }
            .onFailure { Logx.e("could not write $PATH", it) }
    }

    fun clear() = write(0)

    fun shouldArm(): Boolean {
        attempts = read()
        if (attempts >= MAX_FAILED_ARMS) {
            Logx.e("$attempts arms in a row did not survive ${SURVIVED_MS / 1000}s, refusing to arm")
            return false
        }
        write(attempts + 1)
        attempts++
        return true
    }

    fun markSurvivedLater() {
        thread(name = "duckmock-guard", isDaemon = true) {
            Thread.sleep(SURVIVED_MS)
            clear()
            Logx.i("system_server stayed up, safety counter cleared")
        }
    }
}
