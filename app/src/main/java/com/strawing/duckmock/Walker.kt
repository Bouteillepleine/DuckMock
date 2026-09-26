package com.strawing.duckmock

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

object Walker {

    private const val METRES_PER_DEGREE = 111_320.0
    private const val MAX_LATITUDE = 85.0
    private const val MIN_PUSH = 0.02

    class Step(
        val latitude: Double,
        val longitude: Double,
        val bearing: Float,
        val speed: Float,
        val moved: Boolean,
    )

    fun step(
        latitude: Double,
        longitude: Double,
        east: Float,
        north: Float,
        topSpeed: Float,
        seconds: Double,
    ): Step {
        val push = hypot(east.toDouble(), north.toDouble()).coerceAtMost(1.0)
        if (push < MIN_PUSH || seconds <= 0.0 || topSpeed <= 0f) {
            return Step(latitude, longitude, 0f, 0f, false)
        }
        val speed = push * topSpeed
        val distance = speed * seconds.coerceAtMost(5.0)
        val unitEast = east / push
        val unitNorth = north / push

        val nextLat = (latitude + unitNorth * distance / METRES_PER_DEGREE)
            .coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val shrink = cos(Math.toRadians(nextLat)).coerceAtLeast(0.01)
        val nextLon = wrap(longitude + unitEast * distance / (METRES_PER_DEGREE * shrink))

        val bearing = (Math.toDegrees(atan2(unitEast, unitNorth)) + 360.0) % 360.0
        return Step(nextLat, nextLon, bearing.toFloat(), speed.toFloat(), true)
    }

    private fun wrap(longitude: Double): Double {
        var value = longitude
        while (value > 180.0) value -= 360.0
        while (value < -180.0) value += 360.0
        return value
    }
}
