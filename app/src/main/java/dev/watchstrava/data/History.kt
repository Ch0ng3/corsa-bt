package dev.watchstrava.data

import android.content.Context
import dev.watchstrava.engine.Lap
import dev.watchstrava.engine.Summary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Finished workouts, one JSON file each, newest first. */
class History(context: Context) {
    private val dir = File(context.filesDir, "history").apply { mkdirs() }

    fun save(summary: Summary) {
        val tmp = File(dir, "${summary.startedAt}.tmp")
        tmp.writeText(toJson(summary).toString())
        tmp.renameTo(File(dir, "${summary.startedAt}.json"))
    }

    fun all(): List<Summary> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .sortedByDescending { it.name }
        .mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }

    fun get(startedAt: Long): Summary? =
        File(dir, "$startedAt.json").takeIf { it.exists() }
            ?.let { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }

    fun delete(startedAt: Long) {
        File(dir, "$startedAt.json").delete()
    }

    private fun toJson(s: Summary) = JSONObject()
        .put("startedAt", s.startedAt)
        .put("name", s.name)
        .put("movingMs", s.movingMs)
        .put("distanceM", s.distanceM)
        .putOpt("avgHr", s.avgHr)
        .putOpt("maxHr", s.maxHr)
        .putOpt("avgCadence", s.avgCadence)
        .put("zoneMs", JSONArray(s.zoneMs))
        .put("laps", JSONArray(s.laps.map { lap ->
            JSONObject()
                .put("number", lap.number)
                .put("label", lap.label)
                .put("movingMs", lap.movingMs)
                .put("distanceM", lap.distanceM)
                .putOpt("avgHr", lap.avgHr)
                .putOpt("maxHr", lap.maxHr)
        }))

    private fun fromJson(j: JSONObject): Summary {
        val zones = j.getJSONArray("zoneMs")
        val laps = j.getJSONArray("laps")
        return Summary(
            startedAt = j.getLong("startedAt"),
            name = j.getString("name"),
            movingMs = j.getLong("movingMs"),
            distanceM = j.getDouble("distanceM"),
            avgHr = j.optIntOrNull("avgHr"),
            maxHr = j.optIntOrNull("maxHr"),
            avgCadence = j.optIntOrNull("avgCadence"),
            zoneMs = List(zones.length()) { zones.getLong(it) },
            laps = List(laps.length()) { i ->
                val l = laps.getJSONObject(i)
                Lap(
                    number = l.getInt("number"),
                    label = l.getString("label"),
                    movingMs = l.getLong("movingMs"),
                    distanceM = l.getDouble("distanceM"),
                    avgHr = l.optIntOrNull("avgHr"),
                    maxHr = l.optIntOrNull("maxHr"),
                )
            },
        )
    }

    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
}
