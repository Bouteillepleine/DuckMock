package com.strawing.duckmock

import android.content.Context
import com.strawing.duckmock.common.Config
import com.strawing.duckmock.common.MockConfig

object SpoofConfig {

    private const val PREFS = "duckmock_spoof"
    private const val KEY_ADDED_SELF = "claim_added_self"
    private const val KEY_TURNED_ON = "claim_turned_on"

    class Outcome(val ok: Boolean, val message: String)

    fun claim(context: Context): Outcome {
        val config = Root.snapshot().config
            ?: return Outcome(false, "Could not read the module configuration. Is root granted?")

        val addedSelf = !config.spoofers.contains(Config.PKG)
        val turnedOn = !config.grantMockOp
        if (addedSelf) config.spoofers.add(Config.PKG)
        config.grantMockOp = true

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ADDED_SELF, addedSelf)
            .putBoolean(KEY_TURNED_ON, turnedOn)
            .apply()

        return apply(context, config)
    }

    fun release(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val addedSelf = prefs.getBoolean(KEY_ADDED_SELF, true)
        val turnedOn = prefs.getBoolean(KEY_TURNED_ON, true)
        val config = Root.snapshot().config ?: return
        if (addedSelf) config.spoofers.remove(Config.PKG)
        if (turnedOn) config.grantMockOp = false
        apply(context, config)
    }

    private fun apply(context: Context, config: MockConfig): Outcome {
        val pushed = runCatching { ServiceClient.push(context, config) }.getOrDefault(false)
        val written = runCatching { Root.writeConfig(config) }.getOrDefault(false)
        return when {
            pushed -> Outcome(true, "")
            written -> Outcome(
                false,
                "Saved, but the module is not answering, so it only takes effect after a reboot.",
            )
            else -> Outcome(false, "Could not reach the module or write its configuration.")
        }
    }
}
