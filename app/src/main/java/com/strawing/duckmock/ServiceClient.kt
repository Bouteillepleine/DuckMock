package com.strawing.duckmock

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.strawing.duckmock.common.Bridge
import com.strawing.duckmock.common.MockConfig

object ServiceClient {

    @Volatile
    private var cached: IMockService? = null

    fun get(context: Context): IMockService? {
        cached?.let {
            if (it.asBinder().isBinderAlive) return it
            cached = null
        }
        val binder = try {
            context.contentResolver
                .call(Uri.parse(Bridge.URI), Bridge.METHOD, Bridge.ARG, null)
                ?.getBinder(Bridge.KEY_BINDER)
        } catch (_: Throwable) {
            null
        } ?: return null
        return IMockService.Stub.asInterface(binder).also { cached = it }
    }

    fun state(context: Context): Bundle? = runCatching { get(context)?.state }.getOrNull()

    fun records(context: Context): List<Bundle> =
        runCatching { get(context)?.records }.getOrNull() ?: emptyList()

    fun clearRecords(context: Context) {
        runCatching { get(context)?.clearRecords() }
    }

    fun setSpoofing(context: Context, on: Boolean, lat: Double, lon: Double): Boolean {
        val service = get(context) ?: return false
        return runCatching {
            service.pushConfig(Bundle().apply {
                putBoolean(Bridge.STATE_SPOOFING, on)
                putDouble(Bridge.STATE_SPOOF_LAT, lat)
                putDouble(Bridge.STATE_SPOOF_LON, lon)
            })
            true
        }.getOrDefault(false)
    }

    fun push(context: Context, config: MockConfig): Boolean {
        val service = get(context) ?: return false
        return runCatching {
            service.pushConfig(Bundle().apply {
                putBoolean(Bridge.STATE_PAUSED, config.paused)
                putBoolean(Bridge.STATE_HIDE_LOCATION_FLAG, config.hideLocationFlag)
                putBoolean(Bridge.STATE_NORMALIZE_PROVIDER, config.normalizeProvider)
                putBoolean(Bridge.STATE_HIDE_SETTINGS_KEY, config.hideSettingsKey)
                putBoolean(Bridge.STATE_COVER_QUERY, config.coverQueryPath)
                putBoolean(Bridge.STATE_HIDE_APP_OPS, config.hideAppOps)
                putBoolean(Bridge.STATE_GRANT_MOCK_OP, config.grantMockOp)
                putBoolean(Bridge.STATE_SYNTH_GNSS, config.synthesiseGnss)
                putBoolean(Bridge.STATE_HIDE_WIFI, config.hideWifi)
                putBoolean(Bridge.STATE_HIDE_CELLS, config.hideCells)
                putBoolean(Bridge.STATE_VERBOSE, config.verboseLog)
                putStringArrayList(Bridge.STATE_SPOOFERS, ArrayList(config.spoofers))
                putStringArrayList(Bridge.STATE_EXEMPT, ArrayList(config.exempt))
            })
            true
        }.getOrDefault(false)
    }
}
