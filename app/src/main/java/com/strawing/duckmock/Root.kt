package com.strawing.duckmock

import com.strawing.duckmock.common.Config
import com.strawing.duckmock.common.MockConfig
import com.topjohnwu.superuser.Shell

object Root {

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(20)
        )
    }

    class Snapshot(
        val rootAvailable: Boolean,
        val moduleInstalled: Boolean,
        val hooksKilled: Boolean,
        val config: MockConfig?,
        val moduleVersion: String?,
        val zygisk: String,
        val mockLocationSetting: String?,
        val mockOpHolders: List<String>,
    )

    fun warm() {
        runCatching { Shell.getShell() }
    }

    fun available(): Boolean = try {
        Shell.getShell().isRoot
    } catch (_: Throwable) {
        false
    }

    fun snapshot(): Snapshot {
        if (!available()) {
            return Snapshot(false, false, false, null, null, "unknown", null, emptyList())
        }
        val script = buildString {
            append("MD=${Config.MODULE_DIR}\n")
            append("[ -d \$MD ] && echo 'installed=1' || echo 'installed=0'\n")
            append("[ -f ${Config.DISABLE_FILE} ] && echo 'killed=1' || echo 'killed=0'\n")
            append("grep -m1 '^version=' \$MD/module.prop 2>/dev/null | sed 's/^version=/moduleversion=/'\n")
            for (dir in ZYGISK_MODULES) {
                append("grep -m1 '^name=' $dir/module.prop 2>/dev/null | sed 's/^name=/zygisk=/'\n")
            }
            append("echo \"secure=\$(settings get secure ${Config.MOCK_LOCATION_KEY} 2>/dev/null)\"\n")
            append("echo '#holders'\n")
            append("cmd appops query-op ${Config.OPSTR_MOCK_LOCATION} allow 2>/dev/null\n")
            append("echo '#config'\n")
            append("cat ${Config.CONFIG_FILE} 2>/dev/null\n")
        }
        val result = exec(script)
        if (!result.isSuccess && result.out.isEmpty()) {
            return Snapshot(false, false, false, null, null, "unknown", null, emptyList())
        }

        var installed = false
        var killed = false
        var moduleVersion: String? = null
        var zygisk: String? = null
        var secure: String? = null
        val holders = ArrayList<String>()
        val configText = StringBuilder()
        var section = ""

        for (raw in result.out) {
            val line = raw.trim()
            if (line.startsWith("#")) {
                section = line
                continue
            }
            when (section) {
                "#holders" -> {
                    if (line.isNotEmpty() && !line.startsWith("No operations") && line.matches(PACKAGE_PATTERN)) {
                        holders.add(line)
                    }
                }
                "#config" -> configText.append(raw).append('\n')
                else -> when {
                    line == "installed=1" -> installed = true
                    line == "killed=1" -> killed = true
                    line.startsWith("moduleversion=") ->
                        moduleVersion = line.removePrefix("moduleversion=").takeIf { it.isNotEmpty() }
                    line.startsWith("zygisk=") ->
                        if (zygisk == null) zygisk = line.removePrefix("zygisk=").takeIf { it.isNotEmpty() }
                    line.startsWith("secure=") ->
                        secure = line.removePrefix("secure=").takeIf { it.isNotEmpty() && it != "null" }
                }
            }
        }

        val config = configText.toString().takeIf { it.isNotBlank() }?.let { MockConfig.parse(it) }
        return Snapshot(
            rootAvailable = true,
            moduleInstalled = installed,
            hooksKilled = installed && killed,
            config = config,
            moduleVersion = moduleVersion,
            zygisk = zygisk ?: "unknown",
            mockLocationSetting = secure,
            mockOpHolders = holders,
        )
    }

    fun setHooksKilled(killed: Boolean) {
        if (killed) {
            exec("touch ${Config.DISABLE_FILE}", "chmod 0644 ${Config.DISABLE_FILE}")
            relabel(Config.DISABLE_FILE)
        } else {
            exec("rm -f ${Config.DISABLE_FILE}")
        }
    }

    fun writeConfig(config: MockConfig): Boolean {
        val json = config.toJson()
        val result = exec(
            "mkdir -p ${Config.MODULE_DIR}",
            "cat > ${Config.CONFIG_FILE} <<'DUCKMOCK_EOF'\n$json\nDUCKMOCK_EOF",
            "chmod 0644 ${Config.CONFIG_FILE}",
        )
        relabel(Config.CONFIG_FILE)
        mirrorToStagedUpdate()
        return result.isSuccess
    }

    fun refreshDescription() {
        exec("sh ${Config.MODULE_DIR}/describe.sh")
    }

    private fun mirrorToStagedUpdate() {
        if (!exec("test -d ${Config.MODULE_UPDATE_DIR}").isSuccess) return
        exec("cp -af ${Config.CONFIG_FILE} ${Config.MODULE_UPDATE_DIR}/config.json")
    }

    private fun moduleContext(): String? {
        val result = exec("ls -Zd ${Config.MODULE_DIR}/module.prop")
        if (!result.isSuccess) return null
        val token = result.out.firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
        return token?.takeIf { it.count { c -> c == ':' } >= 3 }
    }

    private fun relabel(path: String) {
        val context = moduleContext() ?: return
        exec("chcon $context $path")
    }

    private fun exec(vararg commands: String): Shell.Result = try {
        Shell.cmd(*commands).exec()
    } catch (_: Throwable) {
        FAILED
    }

    private val PACKAGE_PATTERN = Regex("[A-Za-z0-9._]+")

    private val ZYGISK_MODULES = listOf(
        "/data/adb/modules/zygisksu",
        "/data/adb/modules/rezygisk",
        "/data/adb/modules/zygisk_next",
    )

    private val FAILED = object : Shell.Result() {
        override fun getOut(): MutableList<String> = ArrayList()
        override fun getErr(): MutableList<String> = ArrayList()
        override fun getCode(): Int = -1
    }
}
