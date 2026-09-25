package com.strawing.duckmock.zygote.service

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.util.ArrayMap
import com.strawing.duckmock.IMockService
import com.strawing.duckmock.common.Bridge
import com.strawing.duckmock.common.Config
import com.strawing.duckmock.zygote.AppOpsPart
import com.strawing.duckmock.zygote.LocationPart
import com.strawing.duckmock.zygote.SettingsPart
import com.strawing.duckmock.zygote.Targets
import com.strawing.duckmock.zygote.util.Logx
import com.strawing.duckmock.zygote.util.ModuleConfig

class MockService(private val context: Context) : IMockService.Stub() {

    companion object {
        const val VERSION = 1
        private const val MAX_RECORDS = 256
    }

    @Volatile
    var installedAtRealtimeMs = 0L

    private class Rec(
        var count: Int = 0,
        var lastMs: Long = 0L,
        val kinds: MutableSet<String> = LinkedHashSet(4),
    )

    private val lock = Any()
    private val records = ArrayMap<Int, Rec>()

    @Volatile
    private var resolvedAppId = -1

    val callerAppId: Int
        get() {
            if (resolvedAppId >= 0) return resolvedAppId
            val id = runCatching {
                context.packageManager.getApplicationInfo(Config.PKG, 0).uid % Config.PER_USER_RANGE
            }.getOrDefault(-1)
            if (id >= 0) resolvedAppId = id
            return id
        }

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (callerAppId < 0 || uid % Config.PER_USER_RANGE != callerAppId) {
            throw SecurityException("caller uid $uid is not the module")
        }
    }

    fun note(uid: Int, kind: String) {
        synchronized(lock) {
            var rec = records[uid]
            if (rec == null) {
                if (records.size >= MAX_RECORDS) return
                rec = Rec()
                records[uid] = rec
            }
            rec.count++
            rec.lastMs = System.currentTimeMillis()
            if (rec.kinds.size < 8) rec.kinds.add(kind)
        }
    }

    override fun getVersion(): Int {
        enforceCaller()
        return VERSION
    }

    override fun getState(): Bundle {
        enforceCaller()
        val c = ModuleConfig.config
        return Bundle().apply {
            putInt(Bridge.STATE_VERSION, VERSION)
            putInt(Bridge.STATE_LOCATION_HOOKS, LocationPart.hookCount)
            putInt(Bridge.STATE_SETTINGS_HOOKS, SettingsPart.hookCount)
            putInt(Bridge.STATE_APPOPS_HOOKS, AppOpsPart.hookCount)
            putLong(Bridge.STATE_INSTALLED_AT, installedAtRealtimeMs)
            putInt(Bridge.STATE_OP_CODE, AppOpsPart.opCode)
            putBoolean(Bridge.STATE_PAUSED, c.paused)
            putBoolean(Bridge.STATE_HIDE_LOCATION_FLAG, c.hideLocationFlag)
            putBoolean(Bridge.STATE_NORMALIZE_PROVIDER, c.normalizeProvider)
            putBoolean(Bridge.STATE_HIDE_SETTINGS_KEY, c.hideSettingsKey)
            putBoolean(Bridge.STATE_COVER_QUERY, c.coverQueryPath)
            putBoolean(Bridge.STATE_HIDE_APP_OPS, c.hideAppOps)
            putBoolean(Bridge.STATE_GRANT_MOCK_OP, c.grantMockOp)
            putBoolean(Bridge.STATE_VERBOSE, c.verboseLog)
            putString(Bridge.STATE_CLEARED, verdictLine())
            putInt(Bridge.STATE_TOUCHED_APPS, synchronized(lock) { records.size })
        }
    }

    private fun verdictLine(): String = buildString {
        append(LocationPart.verdict())
        append(" · ")
        append(LocationPart.cleared)
        append(" cleared · ")
        append(AppOpsPart.hidden)
        append(" hidden · ")
        append(AppOpsPart.granted)
        append(" granted · ")
        append(SettingsPart.spoofed)
        append(" settings")
    }

    override fun pushConfig(bundle: Bundle) {
        enforceCaller()
        val current = ModuleConfig.config
        val next = current.copy(
            spoofers = LinkedHashSet(current.spoofers),
            exempt = LinkedHashSet(current.exempt),
        )
        if (bundle.containsKey(Bridge.STATE_PAUSED)) next.paused = bundle.getBoolean(Bridge.STATE_PAUSED)
        if (bundle.containsKey(Bridge.STATE_HIDE_LOCATION_FLAG)) next.hideLocationFlag = bundle.getBoolean(Bridge.STATE_HIDE_LOCATION_FLAG)
        if (bundle.containsKey(Bridge.STATE_NORMALIZE_PROVIDER)) next.normalizeProvider = bundle.getBoolean(Bridge.STATE_NORMALIZE_PROVIDER)
        if (bundle.containsKey(Bridge.STATE_HIDE_SETTINGS_KEY)) next.hideSettingsKey = bundle.getBoolean(Bridge.STATE_HIDE_SETTINGS_KEY)
        if (bundle.containsKey(Bridge.STATE_COVER_QUERY)) next.coverQueryPath = bundle.getBoolean(Bridge.STATE_COVER_QUERY)
        if (bundle.containsKey(Bridge.STATE_HIDE_APP_OPS)) next.hideAppOps = bundle.getBoolean(Bridge.STATE_HIDE_APP_OPS)
        if (bundle.containsKey(Bridge.STATE_GRANT_MOCK_OP)) next.grantMockOp = bundle.getBoolean(Bridge.STATE_GRANT_MOCK_OP)
        if (bundle.containsKey(Bridge.STATE_VERBOSE)) next.verboseLog = bundle.getBoolean(Bridge.STATE_VERBOSE)
        bundle.getStringArrayList(Bridge.STATE_SPOOFERS)?.let { next.spoofers = LinkedHashSet(it) }
        bundle.getStringArrayList(Bridge.STATE_EXEMPT)?.let { next.exempt = LinkedHashSet(it) }
        ModuleConfig.config = next
        Logx.verbose = next.verboseLog
        Targets.invalidate()
        Logx.i(
            "config pushed: paused=${next.paused} location=${next.hideLocationFlag} " +
                "settings=${next.hideSettingsKey} appops=${next.hideAppOps} grant=${next.grantMockOp}"
        )
    }

    override fun getRecords(): List<Bundle> {
        enforceCaller()
        synchronized(lock) {
            return records.map { (uid, rec) ->
                Bundle().apply {
                    putInt(Bridge.REC_UID, uid)
                    putInt(Bridge.REC_COUNT, rec.count)
                    putLong(Bridge.REC_LAST, rec.lastMs)
                    putStringArrayList(Bridge.REC_KINDS, ArrayList(rec.kinds))
                }
            }
        }
    }

    override fun clearRecords() {
        enforceCaller()
        synchronized(lock) { records.clear() }
    }
}
