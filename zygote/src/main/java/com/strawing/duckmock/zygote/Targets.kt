package com.strawing.duckmock.zygote

import android.content.Context
import android.os.Binder
import android.util.SparseBooleanArray
import com.strawing.duckmock.common.Config
import com.strawing.duckmock.zygote.util.Logx
import com.strawing.duckmock.zygote.util.ModuleConfig

object Targets {

    private val lock = Any()
    private val cache = SparseBooleanArray()
    private var configStamp = 0

    @Volatile
    var context: Context? = null

    fun adopt(candidate: Context?) {
        if (candidate != null && context == null) context = candidate
    }

    fun systemContext(): Context? {
        context?.let { return it }
        val resolved = runCatching {
            val thread = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
            thread?.javaClass?.getMethod("getSystemContext")
                ?.apply { isAccessible = true }
                ?.invoke(thread) as? Context
        }.getOrNull()
        if (resolved != null) context = resolved
        return resolved
    }

    fun invalidate() {
        synchronized(lock) {
            cache.clear()
            configStamp++
        }
    }

    fun callingUid(): Int? = try {
        Binder.getCallingUid()
    } catch (_: Throwable) {
        null
    }

    fun isSystemUid(uid: Int): Boolean = uid % Config.PER_USER_RANGE < Config.FIRST_APP_UID

    fun packagesOf(uid: Int): Array<String>? {
        val ctx = systemContext() ?: return null
        val token = Binder.clearCallingIdentity()
        return try {
            ctx.packageManager?.getPackagesForUid(uid)
        } catch (_: Throwable) {
            null
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    fun isSpoofer(uid: Int): Boolean {
        val spoofers = ModuleConfig.config.spoofers
        if (spoofers.isEmpty()) return false
        val packages = packagesOf(uid) ?: return false
        return packages.any { it in spoofers }
    }

    fun lieTo(uid: Int): Boolean {
        if (!ModuleConfig.active()) return false
        if (isSystemUid(uid)) return false
        synchronized(lock) {
            val i = cache.indexOfKey(uid)
            if (i >= 0) return cache.valueAt(i)
        }
        val packages = packagesOf(uid)
        if (packages == null) {
            Logx.v { "uid $uid has no packages, telling the truth" }
            return false
        }
        val config = ModuleConfig.config
        val lie = packages.none { config.tellsTruthTo(it) }
        synchronized(lock) { cache.put(uid, lie) }
        return lie
    }

    fun describe(uid: Int): String {
        val packages = packagesOf(uid) ?: return "uid $uid"
        return packages.firstOrNull() ?: "uid $uid"
    }
}
