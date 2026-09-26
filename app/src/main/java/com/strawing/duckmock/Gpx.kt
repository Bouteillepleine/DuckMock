package com.strawing.duckmock

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

object Gpx {

    private val POINT_TAGS = setOf("trkpt", "rtept", "wpt")

    fun read(input: InputStream, fallbackName: String): Route? = runCatching {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val track = ArrayList<RoutePoint>()
        val planned = ArrayList<RoutePoint>()
        val marks = ArrayList<RoutePoint>()

        var name: String? = null
        var basket: ArrayList<RoutePoint>? = null
        var latitude = 0.0
        var longitude = 0.0
        var altitude = 0.0
        var inPoint = false
        var text: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase(Locale.ROOT)
                    if (tag in POINT_TAGS) {
                        inPoint = true
                        latitude = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: Double.NaN
                        longitude = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: Double.NaN
                        altitude = 0.0
                        basket = when (tag) {
                            "trkpt" -> track
                            "rtept" -> planned
                            else -> marks
                        }
                    }
                    text = null
                }

                XmlPullParser.TEXT -> text = parser.text

                XmlPullParser.END_TAG -> {
                    when (parser.name.lowercase(Locale.ROOT)) {
                        "ele" -> if (inPoint) altitude = text?.trim()?.toDoubleOrNull() ?: 0.0
                        "name" -> if (!inPoint && name == null) {
                            name = text?.trim()?.takeIf { it.isNotBlank() }
                        }

                        in POINT_TAGS -> {
                            if (latitude.isFinite() && longitude.isFinite()) {
                                basket?.add(RoutePoint(latitude, longitude, altitude))
                            }
                            inPoint = false
                            basket = null
                        }
                    }
                    text = null
                }
            }
            event = parser.next()
        }

        val points = when {
            track.size >= 2 -> track
            planned.size >= 2 -> planned
            else -> marks
        }
        if (points.size < 2) return null
        Route(name ?: fallbackName, points)
    }.getOrNull()

    fun write(route: Route, output: OutputStream) {
        output.bufferedWriter().use { writer ->
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            writer.write(
                "<gpx version=\"1.1\" creator=\"DuckMock\" " +
                    "xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
            )
            writer.write("  <trk>\n    <name>${escape(route.name)}</name>\n    <trkseg>\n")
            for (point in route.points) {
                writer.write(
                    String.format(
                        Locale.ROOT,
                        "      <trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.1f</ele></trkpt>\n",
                        point.latitude,
                        point.longitude,
                        point.altitude,
                    )
                )
            }
            writer.write("    </trkseg>\n  </trk>\n</gpx>\n")
        }
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
