package dev.watchstrava.data

import android.content.Context
import android.util.Xml
import dev.watchstrava.model.Route
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** GPX courses copied with `adb push x.gpx /sdcard/Android/data/dev.watchstrava/files/routes/`. */
object Routes {
    fun dir(context: Context): File? = context.getExternalFilesDir("routes")

    /** Every readable .gpx in the folder, sorted by name; broken files are skipped. */
    fun load(context: Context): List<Route> =
        dir(context)?.listFiles { f -> f.isFile && f.name.endsWith(".gpx", ignoreCase = true) }.orEmpty()
            .mapNotNull { parse(it) }
            .sortedBy { it.name.lowercase() }

    /** Track points, or route points when the file has no track. Null if unusable. */
    fun parse(file: File): Route? = runCatching {
        val trk = ArrayList<Pair<Double, Double>>()
        val rte = ArrayList<Pair<Double, Double>>()
        var name: String? = null
        var depth = 0
        var nameDepth = -1
        var pointDepth = -1 // inside <wpt>/<trkpt>/<rtept>, whose <name>s are not the route's
        file.inputStream().buffered().use { input ->
            val p = Xml.newPullParser()
            p.setInput(input, null)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        when (p.name) {
                            "wpt" -> pointDepth = depth
                            "trkpt", "rtept" -> {
                                pointDepth = depth
                                val lat = p.getAttributeValue(null, "lat")?.toDoubleOrNull()
                                val lon = p.getAttributeValue(null, "lon")?.toDoubleOrNull()
                                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                                    (if (p.name == "trkpt") trk else rte) += lat to lon
                                }
                            }
                            "name" -> if (name == null && pointDepth < 0) nameDepth = depth
                        }
                    }
                    XmlPullParser.TEXT -> if (depth == nameDepth && name == null) name = p.text.trim().takeIf { it.isNotEmpty() }
                    XmlPullParser.END_TAG -> {
                        if (depth == nameDepth) nameDepth = -1
                        if (depth == pointDepth) pointDepth = -1
                        depth--
                    }
                }
            }
        }
        val pts = if (trk.size >= 2) trk else rte
        if (pts.size < 2) null else Route(name ?: file.nameWithoutExtension, pts, file.name)
    }.getOrNull()
}
