package com.strawing.duckmock

enum class Ending(val label: String) {
    STOP("Stop"),
    LOOP("Loop"),
    BOUNCE("Bounce"),
    ;

    fun next(): Ending = entries[(ordinal + 1) % entries.size]
}

object RouteState {

    private const val RECORD_EVERY_METRES = 4.0

    @Volatile
    var playing: Route? = null
        private set

    @Volatile
    var ending = Ending.STOP

    @Volatile
    var recording = false
        private set

    @Volatile
    private var travelled = 0.0

    @Volatile
    private var direction = 1

    private val taken = ArrayList<RoutePoint>()

    fun play(route: Route, from: Double = 0.0) {
        travelled = from.coerceIn(0.0, route.length)
        direction = 1
        playing = route
    }

    fun stop() {
        playing = null
        travelled = 0.0
        direction = 1
    }

    fun progress(): Float {
        val route = playing ?: return 0f
        if (route.length <= 0.0) return 0f
        return (travelled / route.length).toFloat().coerceIn(0f, 1f)
    }

    fun advance(pace: Float, seconds: Double): Walker.Step? {
        val route = playing ?: return null
        if (!route.usable || pace <= 0f) {
            stop()
            return null
        }
        var next = travelled + pace * seconds.coerceAtMost(5.0) * direction
        var finished = false
        if (direction > 0 && next >= route.length) {
            when (ending) {
                Ending.STOP -> {
                    next = route.length
                    finished = true
                }

                Ending.LOOP -> next -= route.length
                Ending.BOUNCE -> {
                    next = 2 * route.length - next
                    direction = -1
                }
            }
        } else if (direction < 0 && next <= 0.0) {
            when (ending) {
                Ending.STOP -> {
                    next = 0.0
                    finished = true
                }

                Ending.LOOP -> next += route.length
                Ending.BOUNCE -> {
                    next = -next
                    direction = 1
                }
            }
        }
        travelled = next.coerceIn(0.0, route.length)
        val step = sample(route, pace)
        if (finished) stop()
        return step
    }

    private fun sample(route: Route, pace: Float): Walker.Step {
        val leg = route.legAt(travelled)
        val from = route.points[leg]
        val to = route.points.getOrElse(leg + 1) { from }
        val span = route.legLength(leg)
        val share = if (span <= 0.0) 0.0 else ((travelled - route.legStart(leg)) / span).coerceIn(0.0, 1.0)
        return Walker.Step(
            latitude = from.latitude + (to.latitude - from.latitude) * share,
            longitude = from.longitude + (to.longitude - from.longitude) * share,
            bearing = if (direction >= 0) Route.bearing(from, to) else Route.bearing(to, from),
            speed = pace,
            moved = true,
            altitude = from.altitude + (to.altitude - from.altitude) * share,
        )
    }

    fun startRecording() {
        synchronized(taken) { taken.clear() }
        recording = true
    }

    fun record(point: RoutePoint) {
        if (!recording) return
        synchronized(taken) {
            val last = taken.lastOrNull()
            if (last != null && Route.distance(last, point) < RECORD_EVERY_METRES) return
            taken.add(point)
        }
    }

    fun recorded(): List<RoutePoint> = synchronized(taken) { ArrayList(taken) }

    fun recordedCount(): Int = synchronized(taken) { taken.size }

    fun stopRecording(): List<RoutePoint> {
        recording = false
        return recorded()
    }

    fun discardRecording() {
        recording = false
        synchronized(taken) { taken.clear() }
    }
}
