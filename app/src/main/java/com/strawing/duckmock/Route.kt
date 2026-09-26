package com.strawing.duckmock

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

class RoutePoint(val latitude: Double, val longitude: Double, val altitude: Double)

class Route(val name: String, val points: List<RoutePoint>) {

    private val legs = DoubleArray(maxOf(points.size - 1, 0)) { index ->
        distance(points[index], points[index + 1])
    }

    private val cumulative = DoubleArray(points.size).also { marks ->
        for (index in 1 until points.size) marks[index] = marks[index - 1] + legs[index - 1]
    }

    val length: Double = legs.sum()

    val usable: Boolean
        get() = points.size >= 2 && length > 0.0

    fun legLength(index: Int): Double = legs.getOrElse(index) { 0.0 }

    fun legStart(index: Int): Double = cumulative.getOrElse(index) { 0.0 }

    fun legAt(travelled: Double): Int {
        if (legs.isEmpty()) return 0
        var low = 0
        var high = legs.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (cumulative[mid] <= travelled) low = mid else high = mid - 1
        }
        return low
    }

    fun pretty(): String {
        val distance = if (length >= 1000.0) {
            "%.2f km".format(java.util.Locale.ROOT, length / 1000.0)
        } else {
            "%.0f m".format(java.util.Locale.ROOT, length)
        }
        return "${points.size} points · $distance"
    }

    companion object {
        const val METRES_PER_DEGREE = 111_320.0

        private fun offsets(a: RoutePoint, b: RoutePoint): Pair<Double, Double> {
            val north = (b.latitude - a.latitude) * METRES_PER_DEGREE
            val shrink = cos(Math.toRadians((a.latitude + b.latitude) / 2.0)).coerceAtLeast(0.01)
            val east = (b.longitude - a.longitude) * METRES_PER_DEGREE * shrink
            return north to east
        }

        fun distance(a: RoutePoint, b: RoutePoint): Double {
            val (north, east) = offsets(a, b)
            return hypot(north, east)
        }

        fun bearing(a: RoutePoint, b: RoutePoint): Float {
            val (north, east) = offsets(a, b)
            return ((Math.toDegrees(atan2(east, north)) + 360.0) % 360.0).toFloat()
        }
    }
}
