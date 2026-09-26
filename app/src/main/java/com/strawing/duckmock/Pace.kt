package com.strawing.duckmock

enum class Pace(val label: String, val metresPerSecond: Float) {
    STROLL("Stroll", 0.8f),
    WALK("Walk", 1.4f),
    JOG("Jog", 3.1f),
    CYCLE("Cycle", 6.0f),
    DRIVE("Drive", 13.9f),
    ;

    fun pretty(): String = "$label · ${"%.1f".format(java.util.Locale.ROOT, metresPerSecond)} m/s"

    companion object {
        val DEFAULT = WALK

        fun at(index: Int): Pace = entries.getOrElse(index) { DEFAULT }
    }
}
