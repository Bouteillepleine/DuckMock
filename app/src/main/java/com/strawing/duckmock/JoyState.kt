package com.strawing.duckmock

import kotlin.math.hypot

object JoyState {

    @Volatile
    var east = 0f
        private set

    @Volatile
    var north = 0f
        private set

    @Volatile
    var bearing = 0f

    @Volatile
    var speed = 0f

    @Volatile
    var overlayUp = false

    val pushing: Boolean
        get() = hypot(east, north) > 0.02f

    fun aim(east: Float, north: Float) {
        this.east = east
        this.north = north
    }

    fun release() {
        east = 0f
        north = 0f
        speed = 0f
    }
}
