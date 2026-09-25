package com.strawing.duckprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

class SettingsCheck : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        fun say(line: String) = Log.i("DuckProbe", line)
        say("SETTINGS — what this app reads out of Settings.Secure and Settings.Global")

        val cr = context.contentResolver

        val mockGetter = runCatching { Settings.Secure.getString(cr, KEY_MOCK) }
            .getOrElse { "threw ${it.javaClass.simpleName}" }
        say("  mock_location getter         = $mockGetter")
        say("  mock_location appended path  = ${viaAppendedPath(context, SECURE, KEY_MOCK)}")
        say("  mock_location selection      = ${viaSelection(context, SECURE, KEY_MOCK)}")

        val adbGetter = runCatching { Settings.Global.getString(cr, KEY_ADB) }
            .getOrElse { "threw ${it.javaClass.simpleName}" }
        say("  adb_enabled getter           = $adbGetter")
        say("  adb_enabled appended path    = ${viaAppendedPath(context, GLOBAL, KEY_ADB)}")

        val mockHidden = mockGetter.isZeroOrNull()
        val adbHidden = adbGetter.isZeroOrNull()
        say("  VERDICT mock_location: ${if (mockHidden) "reads 0 — hidden" else "reads $mockGetter — VISIBLE"}")
        say("  VERDICT adb_enabled:   ${if (adbHidden) "reads 0 — hidden" else "reads $adbGetter — VISIBLE"}")
        if (mockHidden && adbHidden) {
            say("  Both modules are spoofing through the same provider hook.")
        }
    }

    private fun String?.isZeroOrNull() = this == null || this == "0" || this == "null"

    private fun viaAppendedPath(context: Context, table: String, key: String): String =
        read(context, Uri.parse("content://settings/$table/$key"), null, null)

    private fun viaSelection(context: Context, table: String, key: String): String =
        read(context, Uri.parse("content://settings/$table"), "name=?", arrayOf(key))

    private fun read(
        context: Context,
        uri: Uri,
        selection: String?,
        args: Array<String>?,
    ): String = runCatching {
        context.contentResolver.query(uri, null, selection, args, null).use { c ->
            if (c == null) return "no cursor"
            if (!c.moveToFirst()) return "empty"
            val i = c.getColumnIndex("value")
            if (i < 0) "no value column" else c.getString(i) ?: "null"
        }
    }.getOrElse { "threw ${it.javaClass.simpleName}" }

    private companion object {
        const val SECURE = "secure"
        const val GLOBAL = "global"
        const val KEY_MOCK = "mock_location"
        const val KEY_ADB = "adb_enabled"
    }
}
