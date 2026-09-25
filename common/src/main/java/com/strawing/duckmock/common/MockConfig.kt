package com.strawing.duckmock.common

import org.json.JSONArray
import org.json.JSONObject

data class MockConfig(
    var paused: Boolean = false,
    var hideLocationFlag: Boolean = true,
    var normalizeProvider: Boolean = true,
    var hideSettingsKey: Boolean = true,
    var coverQueryPath: Boolean = true,
    var hideAppOps: Boolean = true,
    var grantMockOp: Boolean = false,
    var synthesiseGnss: Boolean = false,
    var hideWifi: Boolean = true,
    var verboseLog: Boolean = false,
    var spoofers: MutableSet<String> = LinkedHashSet(),
    var exempt: MutableSet<String> = LinkedHashSet(),
) {
    private var raw: String? = null

    fun anyHookWanted(): Boolean =
        hideLocationFlag || hideSettingsKey || hideAppOps || grantMockOp

    fun tellsTruthTo(pkg: String?): Boolean {
        if (pkg == null) return false
        if (pkg == Config.PKG) return true
        if (pkg in Config.SPARE_PACKAGES) return true
        if (pkg in spoofers) return true
        if (pkg in exempt) return true
        return false
    }

    fun toJson(): String {
        val o = runCatching { raw?.let { JSONObject(it) } }.getOrNull() ?: JSONObject()
        o.put(KEY_VERSION, VERSION)
        o.put(KEY_PAUSED, paused)
        o.put(KEY_HIDE_LOCATION_FLAG, hideLocationFlag)
        o.put(KEY_NORMALIZE_PROVIDER, normalizeProvider)
        o.put(KEY_HIDE_SETTINGS_KEY, hideSettingsKey)
        o.put(KEY_COVER_QUERY, coverQueryPath)
        o.put(KEY_HIDE_APP_OPS, hideAppOps)
        o.put(KEY_GRANT_MOCK_OP, grantMockOp)
        o.put(KEY_SYNTH_GNSS, synthesiseGnss)
        o.put(KEY_HIDE_WIFI, hideWifi)
        o.put(KEY_VERBOSE, verboseLog)
        o.put(KEY_SPOOFERS, JSONArray(spoofers.toList()))
        o.put(KEY_EXEMPT, JSONArray(exempt.toList()))
        return o.toString(2)
    }

    companion object {
        const val VERSION = 1

        private const val KEY_VERSION = "version"
        private const val KEY_PAUSED = "paused"
        private const val KEY_HIDE_LOCATION_FLAG = "hideLocationFlag"
        private const val KEY_NORMALIZE_PROVIDER = "normalizeProvider"
        private const val KEY_HIDE_SETTINGS_KEY = "hideSettingsKey"
        private const val KEY_COVER_QUERY = "coverQueryPath"
        private const val KEY_HIDE_APP_OPS = "hideAppOps"
        private const val KEY_GRANT_MOCK_OP = "grantMockOp"
        private const val KEY_SYNTH_GNSS = "synthesiseGnss"
        private const val KEY_HIDE_WIFI = "hideWifi"
        private const val KEY_VERBOSE = "verboseLog"
        private const val KEY_SPOOFERS = "spoofers"
        private const val KEY_EXEMPT = "exempt"

        private fun strings(o: JSONObject, key: String): LinkedHashSet<String> {
            val out = LinkedHashSet<String>()
            o.optJSONArray(key)?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i)?.takeIf { it.isNotEmpty() }?.let(out::add)
                }
            }
            return out
        }

        fun parse(text: String?): MockConfig {
            if (text.isNullOrBlank()) return MockConfig()
            return try {
                val o = JSONObject(text)
                MockConfig(
                    paused = o.optBoolean(KEY_PAUSED, false),
                    hideLocationFlag = o.optBoolean(KEY_HIDE_LOCATION_FLAG, true),
                    normalizeProvider = o.optBoolean(KEY_NORMALIZE_PROVIDER, true),
                    hideSettingsKey = o.optBoolean(KEY_HIDE_SETTINGS_KEY, true),
                    coverQueryPath = o.optBoolean(KEY_COVER_QUERY, true),
                    hideAppOps = o.optBoolean(KEY_HIDE_APP_OPS, true),
                    grantMockOp = o.optBoolean(KEY_GRANT_MOCK_OP, false),
                    synthesiseGnss = o.optBoolean(KEY_SYNTH_GNSS, false),
                    hideWifi = o.optBoolean(KEY_HIDE_WIFI, true),
                    verboseLog = o.optBoolean(KEY_VERBOSE, false),
                    spoofers = strings(o, KEY_SPOOFERS),
                    exempt = strings(o, KEY_EXEMPT),
                ).also { it.raw = text }
            } catch (_: Throwable) {
                MockConfig()
            }
        }
    }
}
