package com.strawing.duckmock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object RouteStore {

    private const val DIR = "routes"
    private const val KEY_NAME = "name"
    private const val KEY_POINTS = "points"

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    private fun slug(name: String): String {
        val cleaned = name.trim().lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
        return cleaned.ifBlank { "route" }.take(48)
    }

    fun list(context: Context): List<Route> =
        dir(context).listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.mapNotNull { read(it) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun named(context: Context, name: String): Route? =
        list(context).firstOrNull { it.name == name }

    fun save(context: Context, route: Route): Boolean = runCatching {
        val document = JSONObject()
        document.put(KEY_NAME, route.name)
        val array = JSONArray()
        for (point in route.points) {
            array.put(
                JSONArray()
                    .put(point.latitude)
                    .put(point.longitude)
                    .put(point.altitude)
            )
        }
        document.put(KEY_POINTS, array)
        File(dir(context), "${slug(route.name)}.json").writeText(document.toString())
        true
    }.getOrDefault(false)

    fun delete(context: Context, name: String) {
        runCatching { File(dir(context), "${slug(name)}.json").delete() }
    }

    private fun read(file: File): Route? = runCatching {
        val document = JSONObject(file.readText())
        val array = document.optJSONArray(KEY_POINTS) ?: return null
        val points = ArrayList<RoutePoint>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONArray(index) ?: continue
            points.add(
                RoutePoint(
                    entry.optDouble(0, Double.NaN),
                    entry.optDouble(1, Double.NaN),
                    entry.optDouble(2, 0.0),
                )
            )
        }
        val clean = points.filter { it.latitude.isFinite() && it.longitude.isFinite() }
        if (clean.size < 2) return null
        Route(document.optString(KEY_NAME, file.nameWithoutExtension), clean)
    }.getOrNull()
}
