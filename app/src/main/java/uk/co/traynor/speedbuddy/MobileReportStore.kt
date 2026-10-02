package uk.co.traynor.speedbuddy

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** Separate, replaceable observation layer. Fixed imports/backups/overrides never write this table. */
class MobileReportStore(private val db: CameraDb) {
    companion object {
        fun createTable(database: SQLiteDatabase) {
            database.execSQL("CREATE TABLE mobile_reports(id TEXT PRIMARY KEY,lat REAL NOT NULL,lon REAL NOT NULL,reported INTEGER NOT NULL,observed INTEGER NOT NULL,expires INTEGER NOT NULL,lifetime INTEGER NOT NULL,direction REAL,road_id TEXT,road_name TEXT,source TEXT NOT NULL)")
            database.execSQL("CREATE INDEX mobile_location ON mobile_reports(lat,lon,expires)")
            database.execSQL("CREATE INDEX mobile_expiry ON mobile_reports(expires)")
        }
    }
    fun activeInBounds(south: Double,west: Double,north: Double,east: Double,nowMs: Long=System.currentTimeMillis()): List<MobileReport> =
        db.readableDatabase.rawQuery("SELECT id,lat,lon,reported,observed,expires,lifetime,direction,road_id,road_name,source FROM mobile_reports WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? AND observed<=? AND expires>? ORDER BY observed DESC LIMIT 500",
            arrayOf(south.toString(),north.toString(),west.toString(),east.toString(),nowMs.toString(),nowMs.toString())).use { c ->
            buildList { while(c.moveToNext()) {
                // A corrupted optional observation must not disable the fixed camera repository.
                runCatching { MobileReport(c.getString(0),GeoPoint(c.getDouble(1),c.getDouble(2)),c.getLong(3),c.getLong(4),c.getLong(5),c.getInt(6),
                    if(c.isNull(7)) null else c.getDouble(7),c.getString(8),c.getString(9),c.getString(10)) }.getOrNull()?.let(::add)
            } }
        }
    fun record(value: MobileReport): MobileReport {
        val database=db.writableDatabase;database.beginTransaction()
        val saved: MobileReport
        try {
            database.delete("mobile_reports","expires<=?",arrayOf(value.reportedAtMs.toString()))
            val previous=activeInBounds(value.point.lat-.001,value.point.lon-.002,value.point.lat+.001,value.point.lon+.002,value.reportedAtMs)
                .firstOrNull { it.sameEncounter(value) }
            saved=previous?.confirm(value.reportedAtMs) ?: value
            // Owner observations are bounded on device, even after repeated manual reports.
            if(previous==null) {
                val count=android.database.DatabaseUtils.queryNumEntries(database,"mobile_reports")
                require(count<500) { "Too many active reports. Wait for older reports to expire." }
            }
            write(saved);database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        OwnerDataRevision.cameras++
        return saved
    }
    fun confirm(id: String,nowMs: Long=System.currentTimeMillis()): Boolean {
        val database=db.writableDatabase;database.beginTransaction()
        var changed=false
        try {
            val active=activeInBounds(-90.0,-180.0,90.0,180.0,nowMs).firstOrNull { it.id==id }
            active?.confirm(nowMs)?.let { write(it);changed=true }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        if(changed) OwnerDataRevision.cameras++
        return changed
    }
    fun remove(id: String) {
        require(id.startsWith("mobile:"))
        if(db.writableDatabase.delete("mobile_reports","id=?",arrayOf(id))>0) OwnerDataRevision.cameras++
    }
    fun prune(nowMs: Long=System.currentTimeMillis()) {
        if(db.writableDatabase.delete("mobile_reports","expires<=?",arrayOf(nowMs.toString()))>0) OwnerDataRevision.cameras++
    }
    private fun write(value: MobileReport) {
        db.writableDatabase.insertWithOnConflict("mobile_reports",null,ContentValues().apply {
            put("id",value.id);put("lat",value.point.lat);put("lon",value.point.lon)
            put("reported",value.reportedAtMs);put("observed",value.observedAtMs);put("expires",value.expiresAtMs)
            put("lifetime",value.lifetimeMinutes);put("direction",value.direction)
            put("road_id",value.roadId);put("road_name",value.roadName);put("source",value.source)
        },SQLiteDatabase.CONFLICT_REPLACE).also { check(it>=0) { "Could not save mobile report" } }
    }
}
