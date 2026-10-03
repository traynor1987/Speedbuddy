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
        // Android cursor windows cannot hold a large JSON row. Read metadata first and
        // materialise text in bounded chunks within the same transaction/snapshot.
        val database=readableDatabase
        database.beginTransactionNonExclusive()
        try {
            val query=if(point==null) "SELECT id,lat,lon,fetched,length(data) FROM regions ORDER BY fetched DESC LIMIT 1" else
                "SELECT id,lat,lon,fetched,length(data) FROM regions WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? ORDER BY fetched DESC"
            val args=point?.let { arrayOf((it.lat-.011).toString(),(it.lat+.011).toString(),(it.lon-.025).toString(),(it.lon+.025).toString()) }
            var selected: Triple<String,Pair<GeoPoint,Long>,Int>?=null
            database.rawQuery(query,args).use { c->
                while(c.moveToNext()) {
                    val center=GeoPoint(c.getDouble(1),c.getDouble(2));val fetched=c.getLong(3)
                    if(point==null || OsmSnapshot(center,fetched,emptyList(),emptyList()).usable(point,System.currentTimeMillis())) {
                        selected=Triple(c.getString(0),center to fetched,c.getInt(4));break
                    }
                }
            }
            val record=selected ?: return null
            require(record.third<=8_000_000) { "Saved road region is too large" }
            val text=StringBuilder(record.third)
            var offset=1
            while(offset<=record.third) {
                database.rawQuery("SELECT substr(data,?,131072) FROM regions WHERE id=?",arrayOf(offset.toString(),record.first)).use { c->
                    check(c.moveToFirst());text.append(c.getString(0))
                }
                offset+=131072
            }
            database.setTransactionSuccessful()
            return CachedRoadRegion(record.second.first,record.second.second,text.toString())
        } finally { database.endTransaction() }
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
