package uk.co.traynor.speedbuddy

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

class CameraDb(context: Context) : SQLiteOpenHelper(context, "cameras.db", null, 2), CameraRepository {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cameras(id TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, type TEXT NOT NULL, direction REAL, mph INTEGER, note TEXT, updated INTEGER NOT NULL)")
        createImported(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if (oldVersion < 2) createImported(db) }
    private fun createImported(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE imported_cameras(id TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, type TEXT NOT NULL)")
        db.execSQL("CREATE INDEX imported_location ON imported_cameras(lat,lon)")
        db.execSQL("CREATE TABLE imported_info(imported_at INTEGER NOT NULL, source_date TEXT NOT NULL, count INTEGER NOT NULL)")
    }
    data class ImportedInfo(val importedAtMs: Long, val sourceDate: String, val count: Int)
    fun importedInfo(): ImportedInfo? = readableDatabase.rawQuery("SELECT imported_at,source_date,count FROM imported_info LIMIT 1", null).use {
        if (it.moveToFirst()) ImportedInfo(it.getLong(0), it.getString(1), it.getInt(2)) else null
    }
    fun importedNearby(point: GeoPoint): List<Camera> = readableDatabase.rawQuery(
        "SELECT id,lat,lon,type FROM imported_cameras WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
        arrayOf((point.lat - .015).toString(), (point.lat + .015).toString(),
            (point.lon - .025).toString(), (point.lon + .025).toString())).use { cursor ->
        buildList { while (cursor.moveToNext()) add(Camera(cursor.getString(0), GeoPoint(cursor.getDouble(1), cursor.getDouble(2)),
            CameraType.valueOf(cursor.getString(3)), CameraSource.LUFOP)) }
    }
    /** Replaces only the downloaded layer. Owner cameras and OSM cache remain untouched. */
    fun replaceImported(cameras: List<Camera>, sourceDate: String): Int {
        require(cameras.isNotEmpty() && cameras.all { it.source == CameraSource.LUFOP })
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete("imported_cameras", null, null)
            val statement = database.compileStatement("INSERT INTO imported_cameras(id,lat,lon,type) VALUES(?,?,?,?)")
            cameras.forEach { camera ->
                statement.clearBindings(); statement.bindString(1, camera.id)
                statement.bindDouble(2, camera.point.lat); statement.bindDouble(3, camera.point.lon)
                statement.bindString(4, camera.type.name); statement.executeInsert()
            }
            statement.close()
            database.delete("imported_info", null, null)
            database.insertOrThrow("imported_info", null, ContentValues().apply {
                put("imported_at", System.currentTimeMillis()); put("source_date", sourceDate); put("count", cameras.size)
            })
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        return cameras.size
    }
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
    fun merge(cameras: List<Camera>) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            cameras.forEach { camera ->
                require(camera.source == CameraSource.USER)
                val values = ContentValues().apply {
                    put("id", camera.id); put("lat", camera.point.lat); put("lon", camera.point.lon)
                    put("type", camera.type.name); put("direction", camera.direction)
                    put("mph", camera.enforcedMph); put("note", camera.note); put("updated", camera.updatedAtMs)
                }
                if (database.insertWithOnConflict("cameras", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0)
                    error("Could not restore camera")
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }
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

/** Reads the pre-0.2 single extract only, for one-time migration into RoadDb. */
class LegacyOsmCache(private val context: Context) {
    private val cacheFile get() = File(context.filesDir, "osm-snapshot.json")
    fun cached(): OsmSnapshot? = runCatching { decode(JSONObject(cacheFile.readText()), cacheFile.lastModified()) }.getOrNull()
    private fun decode(json: JSONObject, fetched: Long): OsmSnapshot {
        val center = run {
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
        return OsmSnapshot(center, fetched, roads, cameras.values.toList())
    }
    private fun category(enforcement: String, highway: String): CameraType? = when {
        "traffic_signals" in enforcement || "red_light_camera" in enforcement -> CameraType.RED_LIGHT
        "maxspeed" in enforcement || highway == "speed_camera" -> CameraType.SPEED
        else -> null
    }
    private fun tagMap(tags: JSONObject): Map<String, String> = tags.keys().asSequence().associateWith { tags.getString(it) }
}
