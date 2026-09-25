package com.strawing.duckprobe

import android.app.AppOpsManager
import android.content.ContentResolver
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Process
import android.provider.Settings

object Probe {

    private const val KEY = "mock_location"
    private const val OPSTR = "android:mock_location"
    private val PROVIDERS = listOf("gps", "fused", "network", "passive")

    fun run(context: Context): List<String> {
        val out = ArrayList<String>()
        out.add("uid ${Process.myUid()} · ${context.packageName}")
        out.add("")
        out.add("SETTINGS — secure/$KEY")
        settings(context.contentResolver, out)
        out.add("")
        out.add("APP-OPS — $OPSTR")
        appOps(context, out)
        out.add("")
        out.add("LOCATION — is the fix marked as mock")
        location(context, out)
        return out
    }

    private fun settings(cr: ContentResolver, out: MutableList<String>) {
        out.add("  getString            = ${describe(runCatching { Settings.Secure.getString(cr, KEY) })}")

        out.add("  query name=?         = ${describe(runCatching { queryValue(cr, Settings.Secure.CONTENT_URI, "name=?", arrayOf(KEY)) })}")

        val appended = Uri.withAppendedPath(Settings.Secure.CONTENT_URI, KEY)
        out.add("  query appended path  = ${describe(runCatching { queryValue(cr, appended, null, null) })}")

        out.add("  bulk sweep           = ${describe(runCatching { bulkValue(cr) })}")
    }

    private fun queryValue(
        cr: ContentResolver,
        uri: Uri,
        selection: String?,
        args: Array<String>?,
    ): String? {
        cr.query(uri, null, selection, args, null)?.use { c ->
            val nameIdx = c.getColumnIndex("name")
            val valueIdx = c.getColumnIndex("value")
            if (valueIdx < 0) return null
            while (c.moveToNext()) {
                if (nameIdx < 0 || c.getString(nameIdx) == KEY) return c.getString(valueIdx)
            }
        }
        return null
    }

    private fun bulkValue(cr: ContentResolver): String? {
        cr.query(Settings.Secure.CONTENT_URI, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex("name")
            val valueIdx = c.getColumnIndex("value")
            if (nameIdx < 0 || valueIdx < 0) return null
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == KEY) return c.getString(valueIdx)
            }
        }
        return null
    }

    private fun appOps(context: Context, out: MutableList<String>) {
        val manager = context.getSystemService(AppOpsManager::class.java)
        if (manager == null) {
            out.add("  no AppOpsManager")
            return
        }
        val mode = runCatching {
            manager.unsafeCheckOpNoThrow(OPSTR, Process.myUid(), context.packageName)
        }
        out.add("  am I the mock app    = ${modeName(mode.getOrNull())}${mode.exceptionOrNull()?.let { " (${it.javaClass.simpleName})" } ?: ""}")
        out.add("  note: an app may only ask about itself, the system refuses any other uid")
    }

    private fun modeName(mode: Int?): String = when (mode) {
        null -> "error"
        AppOpsManager.MODE_ALLOWED -> "ALLOWED — this app is a mock location app"
        AppOpsManager.MODE_IGNORED -> "IGNORED"
        AppOpsManager.MODE_ERRORED -> "ERRORED — not a mock location app"
        AppOpsManager.MODE_DEFAULT -> "DEFAULT"
        else -> "mode $mode"
    }

    private fun location(context: Context, out: MutableList<String>) {
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm == null) {
            out.add("  no LocationManager")
            return
        }
        val providers = runCatching { lm.allProviders }.getOrDefault(emptyList())
        out.add("  providers            = ${providers.joinToString(", ")}")
        val odd = providers.filter { it !in setOf("gps", "network", "passive", "fused") }
        if (odd.isNotEmpty()) out.add("  unexpected providers = ${odd.joinToString(", ")}")

        var any = false
        for (name in PROVIDERS) {
            val location = runCatching { lm.getLastKnownLocation(name) }.getOrNull() ?: continue
            any = true
            out.add("  $name".padEnd(23) + "= ${describeLocation(location)}")
        }
        if (!any) {
            out.add("  no last known fix on any provider")
            out.add("  open a map app or start your spoofer, then run the probe again")
        }
    }

    fun describeLocation(location: Location): String {
        val mock = runCatching {
            Location::class.java.getDeclaredMethod("isMock")
                .apply { isAccessible = true }
                .invoke(location) as? Boolean
        }.getOrNull()
        val legacy = runCatching {
            Location::class.java.getDeclaredMethod("isFromMockProvider")
                .apply { isAccessible = true }
                .invoke(location) as? Boolean
        }.getOrNull()
        val extra = runCatching { location.extras?.get("mockLocation") }.getOrNull()
        return buildString {
            append("isMock=")
            append(mock ?: "?")
            append(" isFromMockProvider=")
            append(legacy ?: "?")
            append(" provider=")
            append(location.provider ?: "?")
            if (extra != null) {
                append(" extra.mockLocation=")
                append(extra)
            }
        }
    }

    private fun describe(result: Result<String?>): String {
        val error = result.exceptionOrNull()
        if (error != null) return "threw ${error.javaClass.simpleName}"
        return result.getOrNull() ?: "null"
    }
}
