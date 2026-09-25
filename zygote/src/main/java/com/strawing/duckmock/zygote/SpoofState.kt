package com.strawing.duckmock.zygote

object SpoofState {

    @Volatile
    var active = false
        private set

    @Volatile
    var latitude = 0.0
        private set

    @Volatile
    var longitude = 0.0
        private set

    fun set(on: Boolean, lat: Double, lon: Double): Boolean {
        latitude = lat
        longitude = lon
        val changed = on != active
        active = on
        return changed
    }
}
