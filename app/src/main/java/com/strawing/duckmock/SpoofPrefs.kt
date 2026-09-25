package com.strawing.duckmock

import android.content.Context

data class SpoofTarget(
    val latitude: Double = 48.8566,
    val longitude: Double = 2.3522,
    val altitude: Double = 35.0,
    val accuracy: Float = 8f,
    val jitterMetres: Float = 3f,
    val label: String = "",
) {
    fun pretty(): String =
        String.format(java.util.Locale.ROOT, "%.6f, %.6f", latitude, longitude)
}

object SpoofPrefs {

    private const val PREFS = "duckmock_spoof"
    private const val LAT = "lat"
    private const val LON = "lon"
    private const val ALT = "alt"
    private const val ACC = "acc"
    private const val JITTER = "jitter"
    private const val LABEL = "label"
    private const val RUNNING = "running"
    private const val FAVOURITES = "favourites"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun target(context: Context): SpoofTarget {
        val p = prefs(context)
        val fallback = SpoofTarget()
        return SpoofTarget(
            latitude = java.lang.Double.longBitsToDouble(
                p.getLong(LAT, java.lang.Double.doubleToRawLongBits(fallback.latitude))
            ),
            longitude = java.lang.Double.longBitsToDouble(
                p.getLong(LON, java.lang.Double.doubleToRawLongBits(fallback.longitude))
            ),
            altitude = java.lang.Double.longBitsToDouble(
                p.getLong(ALT, java.lang.Double.doubleToRawLongBits(fallback.altitude))
            ),
            accuracy = p.getFloat(ACC, fallback.accuracy),
            jitterMetres = p.getFloat(JITTER, fallback.jitterMetres),
            label = p.getString(LABEL, "") ?: "",
        )
    }

    fun setTarget(context: Context, target: SpoofTarget) {
        prefs(context).edit()
            .putLong(LAT, java.lang.Double.doubleToRawLongBits(target.latitude))
            .putLong(LON, java.lang.Double.doubleToRawLongBits(target.longitude))
            .putLong(ALT, java.lang.Double.doubleToRawLongBits(target.altitude))
            .putFloat(ACC, target.accuracy)
            .putFloat(JITTER, target.jitterMetres)
            .putString(LABEL, target.label)
            .apply()
    }

    fun running(context: Context): Boolean = prefs(context).getBoolean(RUNNING, false)

    fun setRunning(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(RUNNING, value).apply()
    }

    fun favourites(context: Context): List<SpoofTarget> {
        val raw = prefs(context).getStringSet(FAVOURITES, emptySet()) ?: emptySet()
        return raw.mapNotNull { decode(it) }.sortedBy { it.label.lowercase() }
    }

    fun addFavourite(context: Context, target: SpoofTarget) {
        if (target.label.isBlank()) return
        val current = LinkedHashSet(prefs(context).getStringSet(FAVOURITES, emptySet()) ?: emptySet())
        current.removeAll { decode(it)?.label == target.label }
        current.add(encode(target))
        prefs(context).edit().putStringSet(FAVOURITES, current).apply()
    }

    fun removeFavourite(context: Context, label: String) {
        val current = LinkedHashSet(prefs(context).getStringSet(FAVOURITES, emptySet()) ?: emptySet())
        current.removeAll { decode(it)?.label == label }
        prefs(context).edit().putStringSet(FAVOURITES, current).apply()
    }

    private fun encode(t: SpoofTarget): String =
        "${t.label}\u001f${t.latitude}\u001f${t.longitude}\u001f${t.altitude}\u001f${t.accuracy}\u001f${t.jitterMetres}"

    private fun decode(raw: String): SpoofTarget? {
        val parts = raw.split('\u001f')
        if (parts.size < 6) return null
        return SpoofTarget(
            label = parts[0],
            latitude = parts[1].toDoubleOrNull() ?: return null,
            longitude = parts[2].toDoubleOrNull() ?: return null,
            altitude = parts[3].toDoubleOrNull() ?: 0.0,
            accuracy = parts[4].toFloatOrNull() ?: 8f,
            jitterMetres = parts[5].toFloatOrNull() ?: 0f,
        )
    }
}
