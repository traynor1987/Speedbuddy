package uk.co.traynor.speedbuddy

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.cos

interface SpeedLimitProvider { fun limit(match: RoadMatch?): Int? }
class OsmSpeedLimitProvider : SpeedLimitProvider {
    override fun limit(match: RoadMatch?): Int? = match?.takeIf { it.confidence >= .35 }?.road?.tags?.let(SpeedLimits::mph)
}
interface CameraRepository {
    fun userCameras(): List<Camera>
    fun upsert(camera: Camera)
    fun delete(id: String)
}

class CameraDb(context: Context) : SQLiteOpenHelper(context, "cameras.db", null, 1), CameraRepository {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cameras(id TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, type TEXT NOT NULL, direction REAL, mph INTEGER, note TEXT, updated INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    override fun userCameras(): List<Camera> = readableDatabase.rawQuery("SELECT id,lat,lon,type,direction,mph,note,updated FROM cameras", null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(Camera(cursor.getString(0), GeoPoint(cursor.getDouble(1), cursor.getDouble(2)),
                CameraType.valueOf(cursor.getString(3)), CameraSource.USER,
                if (cursor.isNull(4)) null else cursor.getDouble(4), if (cursor.isNull(5)) null else cursor.getInt(5),
                cursor.getString(6), cursor.getLong(7)))
        }
    }
    override fun upsert(camera: Camera) {
        require(camera.source == CameraSource.USER)
        val values = ContentValues().apply {
            put("id", camera.id); put("lat", camera.point.lat); put("lon", camera.point.lon)
            put("type", camera.type.name); put("direction", camera.direction); put("mph", camera.enforcedMph)
            put("note", camera.note); put("updated", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("cameras", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
    override fun delete(id: String) { writableDatabase.delete("cameras", "id=?", arrayOf(id)) }
    fun create(point: GeoPoint, type: CameraType, direction: Double? = null, mph: Int? = null, note: String? = null): Camera {
        val camera = Camera(UUID.randomUUID().toString(), point, type, CameraSource.USER, direction, mph, note)
        upsert(camera); return camera
    }
}

data class OsmSnapshot(val center: GeoPoint, val fetchedAt: Long, val roads: List<Road>, val cameras: List<Camera>) {
    // Query covers a 1.5 km latitude/longitude box around the centre. Leave a safety margin.
    fun usable(point: GeoPoint, now: Long): Boolean = now - fetchedAt in 0..86_400_000 &&
        kotlin.math.abs(point.lat - center.lat) < 1150.0 / 111195.0 &&
        kotlin.math.abs(point.lon - center.lon) < 1150.0 / (111320.0 * cos(Math.toRadians(center.lat)))
}

object OsmCoverage {
    fun refreshTarget(snapshot: OsmSnapshot?, fix: Fix, nowMs: Long, lastAttemptMs: Long): GeoPoint? {
        if (lastAttemptMs > 0 && nowMs - lastAttemptMs < 15_000) return null
        if (snapshot == null || !snapshot.usable(fix.point, nowMs)) return fix.point
        val distance = Geo.distance(snapshot.center, fix.point)
        val movingAway = fix.bearing?.let { Geo.difference(it, Geo.bearing(snapshot.center, fix.point)) < 70 } ?: false
        if (distance < 250 || !movingAway) return null
        return fix.bearing?.let { Geo.ahead(fix.point, it, 900.0) } ?: fix.point
    }
}

class OsmDataSource(private val context: Context) {
    private val cacheFile get() = File(context.filesDir, "osm-snapshot.json")
    fun cached(): OsmSnapshot? = runCatching { decode(JSONObject(cacheFile.readText()), cacheFile.lastModified()) }.getOrNull()
    fun fetch(point: GeoPoint): OsmSnapshot {
        val latStep = 1.5 / 111.195; val lonStep = 1.5 / (111.32 * cos(Math.toRadians(point.lat)))
        val box = "${point.lat - latStep},${point.lon - lonStep},${point.lat + latStep},${point.lon + lonStep}"
        val query = """[out:json][timeout:25];(way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"]($box);node["highway"="speed_camera"]($box);node["enforcement"~"maxspeed|traffic_signals|red_light_camera"]($box);relation["type"="enforcement"]["enforcement"~"maxspeed|traffic_signals|red_light_camera"]($box););out geom;"""
        val connection = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 8000; connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "SpeedBuddy/0.1 (owner-first driving utility)")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.outputStream.use { it.write("data=${java.net.URLEncoder.encode(query, "UTF-8")}".toByteArray()) }
            if (connection.responseCode != 200) throw IllegalStateException("Overpass HTTP ${connection.responseCode}")
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            if (response.length > 8_000_000) throw IllegalStateException("Map response too large")
            val snapshot = decode(JSONObject(response), System.currentTimeMillis(), point)
            val temp = File(context.filesDir, "osm-snapshot.tmp")
            temp.writeText(response)
            if (!temp.renameTo(cacheFile)) throw IllegalStateException("Could not save map cache")
            return snapshot
        } finally { connection.disconnect() }
    }
    private fun decode(json: JSONObject, fetched: Long, centerOverride: GeoPoint? = null): OsmSnapshot {
        val center = centerOverride ?: run {
            val saved = context.getSharedPreferences("cache", Context.MODE_PRIVATE)
            GeoPoint(saved.getString("lat", "0")!!.toDouble(), saved.getString("lon", "0")!!.toDouble())
        }
        val roads = mutableListOf<Road>(); val cameras = linkedMapOf<String, Camera>()
        val elements = json.getJSONArray("elements")
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i); val kind = element.getString("type")
            val tags = element.optJSONObject("tags") ?: JSONObject()
            if (kind == "way") {
                val geometry = element.optJSONArray("geometry") ?: continue
                val points = (0 until geometry.length()).map { geometry.getJSONObject(it) }.map { GeoPoint(it.getDouble("lat"), it.getDouble("lon")) }
                if (points.size > 1) roads += Road("way/${element.getLong("id")}", tags.optString("name").takeIf { it.isNotBlank() }, points, tagMap(tags))
            } else if (kind == "node") {
                val type = category(tags.optString("enforcement"), tags.optString("highway")) ?: continue
                val id = "node/${element.getLong("id")}"; cameras[id] = Camera(id, GeoPoint(element.getDouble("lat"), element.getDouble("lon")), type,
                    CameraSource.OSM, tags.optString("direction").toDoubleOrNull(), SpeedLimits.mph(tagMap(tags)), updatedAtMs = fetched)
            } else if (kind == "relation") {
                val type = category(tags.optString("enforcement"), "") ?: continue
                val members = element.optJSONArray("members") ?: continue
                for (j in 0 until members.length()) {
                    val member = members.getJSONObject(j)
                    if (member.optString("role") != "device" || member.optString("type") != "node") continue
                    val id = "node/${member.getLong("ref")}"; if (id in cameras) continue
                    if (!member.has("lat") || !member.has("lon")) continue
                    cameras[id] = Camera(id, GeoPoint(member.getDouble("lat"), member.getDouble("lon")), type,
                        CameraSource.OSM, tags.optString("direction").toDoubleOrNull(), SpeedLimits.mph(tagMap(tags)), updatedAtMs = fetched)
                }
            }
        }
        if (centerOverride != null) context.getSharedPreferences("cache", Context.MODE_PRIVATE).edit()
            .putString("lat", center.lat.toString()).putString("lon", center.lon.toString()).apply()
        return OsmSnapshot(center, fetched, roads, cameras.values.toList())
    }
    private fun category(enforcement: String, highway: String): CameraType? = when {
        "traffic_signals" in enforcement || "red_light_camera" in enforcement -> CameraType.RED_LIGHT
        "maxspeed" in enforcement || highway == "speed_camera" -> CameraType.SPEED
        else -> null
    }
    private fun tagMap(tags: JSONObject): Map<String, String> = tags.keys().asSequence().associateWith { tags.getString(it) }
}
