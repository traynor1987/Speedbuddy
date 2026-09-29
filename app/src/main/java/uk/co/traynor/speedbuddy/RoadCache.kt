package uk.co.traynor.speedbuddy

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class CachedRoadRegion(val center: GeoPoint, val fetchedAt: Long, val json: String)

/** Geometry and its centre are committed together. Map and Drive share up to 128 visited regions. */
class RoadCache(context: Context, name: String = "road-cache.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE regions(id TEXT PRIMARY KEY,lat REAL NOT NULL,lon REAL NOT NULL,fetched INTEGER NOT NULL,data TEXT NOT NULL)")
        db.execSQL("CREATE INDEX regions_location ON regions(lat,lon)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun latest(point: GeoPoint? = null): CachedRoadRegion? {
        val query = if (point == null) "SELECT lat,lon,fetched,data FROM regions ORDER BY fetched DESC LIMIT 1" else
            "SELECT lat,lon,fetched,data FROM regions WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? ORDER BY fetched DESC"
        val args = point?.let { arrayOf((it.lat-.011).toString(),(it.lat+.011).toString(),(it.lon-.025).toString(),(it.lon+.025).toString()) }
        return readableDatabase.rawQuery(query,args).use { c ->
            var found: CachedRoadRegion? = null
            while(c.moveToNext()) {
                val candidate = CachedRoadRegion(GeoPoint(c.getDouble(0),c.getDouble(1)),c.getLong(2),c.getString(3))
                if (point == null || OsmSnapshot(candidate.center,candidate.fetchedAt,emptyList(),emptyList()).usable(point,System.currentTimeMillis())) {
                    found=candidate; break
                }
            }
            found
        }
    }
    fun save(region: CachedRoadRegion) {
        val database=writableDatabase
        database.beginTransaction()
        try {
            val id="${(region.center.lat*100).toInt()}:${(region.center.lon*100).toInt()}"
            check(database.insertWithOnConflict("regions",null,ContentValues().apply {
                put("id",id);put("lat",region.center.lat);put("lon",region.center.lon);put("fetched",region.fetchedAt);put("data",region.json)
            }, SQLiteDatabase.CONFLICT_REPLACE)>=0) { "Could not cache road region" }
            database.execSQL("DELETE FROM regions WHERE id NOT IN (SELECT id FROM regions ORDER BY fetched DESC LIMIT 128)")
            while (diagnostics().second > 80_000_000) database.execSQL("DELETE FROM regions WHERE id=(SELECT id FROM regions ORDER BY fetched ASC LIMIT 1)")
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }
    fun diagnostics(): Pair<Int,Long> = readableDatabase.rawQuery("SELECT count(*),coalesce(sum(length(data)),0) FROM regions",null).use { it.moveToFirst(); it.getInt(0) to it.getLong(1) }
}
