package com.strawing.duckmock.zygote.hook

import com.strawing.duckmock.zygote.util.Logx
import java.lang.reflect.InvocationHandler
import java.util.concurrent.ConcurrentHashMap

object SpoofRegistry {

    private const val KEY = "duck.hook.settings.filter"

    const val CALL = "call"
    const val QUERY = "query"

    private val filters = ConcurrentHashMap<String, InvocationHandler>()

    private val registry = InvocationHandler { _, _, args ->
        val name = args?.getOrNull(0) as? String
        val handler = args?.getOrNull(1) as? InvocationHandler
        if (name == null || handler == null) {
            false
        } else {
            filters[name] = handler
            Logx.i("registered the settings filter of $name")
            true
        }
    }

    fun publish() {
        runCatching { System.getProperties()[KEY] = registry }
            .onSuccess { Logx.i("published the settings filter registry") }
            .onFailure { Logx.e("could not publish the settings filter registry", it) }
    }

    fun registerWithOwner(name: String, handler: InvocationHandler): Boolean {
        val owner = runCatching {
            System.getProperties()[KEY] as? InvocationHandler
        }.getOrNull()
        if (owner == null || owner === registry) return false
        val ok = runCatching {
            owner.invoke(null, null, arrayOf<Any?>(name, handler)) == true
        }.getOrDefault(false)
        Logx.i("handing our settings filter to the module that owns the provider hook: $ok")
        return ok
    }

    fun apply(kind: String, uid: Int, args: List<Any?>, result: Any?): Any? {
        if (filters.isEmpty()) return null
        var current = result
        var changed = false
        for ((name, handler) in filters) {
            val next = runCatching {
                handler.invoke(null, null, arrayOf<Any?>(kind, uid, args.toTypedArray(), current))
            }.onFailure { Logx.e("settings filter of $name failed", it) }.getOrNull() ?: continue
            current = next
            changed = true
        }
        return if (changed) current else null
    }
}
