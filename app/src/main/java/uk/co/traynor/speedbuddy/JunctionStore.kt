package uk.co.traynor.speedbuddy

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** Metadata decorates permanent owner cameras; imports and temporary reports remain separate. */
class JunctionStore(private val db: CameraDb) {
    fun all(): List<CameraJunction> = db.readableDatabase.rawQuery(
        "SELECT id,name,lat,lon,ways FROM camera_junctions ORDER BY name LIMIT 501", null).use { c ->
        buildList { while (c.moveToNext()) add(CameraJunction(c.getString(0), c.getString(1),
            GeoPoint(c.getDouble(2), c.getDouble(3)), c.getInt(4))) }
    }
    fun find(id: String): CameraJunction? = db.readableDatabase.rawQuery(
        "SELECT id,name,lat,lon,ways FROM camera_junctions WHERE id=?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) CameraJunction(c.getString(0), c.getString(1), GeoPoint(c.getDouble(2), c.getDouble(3)), c.getInt(4)) else null
    }
    fun save(junction: CameraJunction) = save(junction,emptySet())
    /** Incoming cameras are validated against the stored centre by CameraDb.merge in the same transaction. */
    internal fun restore(junction: CameraJunction, replacingIds: Set<String>) = save(junction,replacingIds)
    private fun save(junction: CameraJunction, replacingIds: Set<String>) {
        JunctionRules.validate(junction)
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            require(find(junction.id) != null || all().size < 500) { "Maximum 500 junctions" }
            db.userCameras().filter { it.junction?.id == junction.id && it.id !in replacingIds }
                .forEach { JunctionRules.validateMember(it, junction) }
            database.insertOrThrow("camera_junctions", null, ContentValues().apply {
                put("id", junction.id); put("name", junction.name); put("lat", junction.point.lat)
                put("lon", junction.point.lon); put("ways", junction.ways)
            }.also { database.delete("camera_junctions", "id=?", arrayOf(junction.id)) })
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        OwnerDataRevision.cameras++
    }
    fun attach(cameras: List<Camera>): List<Camera> {
        val owner = cameras.filter { it.source == CameraSource.USER }
        if (owner.isEmpty()) return cameras
        val groups = all().associateBy { it.id }
        val memberships = mutableMapOf<String, String>()
        owner.chunked(400).forEach { chunk ->
            db.readableDatabase.rawQuery("SELECT camera_id,junction_id FROM junction_members WHERE camera_id IN (${chunk.joinToString(",") { "?" }})",
                chunk.map { it.id }.toTypedArray()).use { c ->
                while (c.moveToNext()) memberships[c.getString(0)] = c.getString(1)
            }
        }
        return cameras.map { camera -> camera.copy(junction = memberships[camera.id]?.let(groups::get)) }
    }
    fun setMembership(camera: Camera) {
        val junction = camera.junction
        if (junction != null) {
            val stored = find(junction.id) ?: error("Junction no longer exists")
            JunctionRules.validateMember(camera, stored)
            db.writableDatabase.insertWithOnConflict("junction_members", null, ContentValues().apply {
                put("camera_id", camera.id); put("junction_id", stored.id)
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it >= 0) { "Could not group camera" } }
        } else db.writableDatabase.delete("junction_members", "camera_id=?", arrayOf(camera.id))
    }
    fun remove(id: String) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            database.delete("junction_members", "junction_id=?", arrayOf(id))
            database.delete("camera_junctions", "id=?", arrayOf(id))
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        OwnerDataRevision.cameras++
    }
    companion object {
        fun createTables(database: SQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS camera_junctions(id TEXT PRIMARY KEY,name TEXT NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,ways INTEGER NOT NULL CHECK(ways IN (4,5)))")
            database.execSQL("CREATE TABLE IF NOT EXISTS junction_members(camera_id TEXT PRIMARY KEY,junction_id TEXT NOT NULL)")
            database.execSQL("CREATE INDEX IF NOT EXISTS junction_members_group ON junction_members(junction_id)")
            database.execSQL("CREATE INDEX IF NOT EXISTS junction_location ON camera_junctions(lat,lon)")
        }
    }
}
