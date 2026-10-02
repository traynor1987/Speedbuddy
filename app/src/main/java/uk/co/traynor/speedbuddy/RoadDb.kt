package uk.co.traynor.speedbuddy

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos

/** Public tile storage is independent of owner cameras, settings and local corrections. */
class RoadDb(context: Context,name: String = "roads.db") : SQLiteOpenHelper(context,name,null,1) {
    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA auto_vacuum=INCREMENTAL") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tiles(id TEXT PRIMARY KEY,fetched INTEGER NOT NULL,bytes INTEGER NOT NULL,complete INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE roads(pk INTEGER PRIMARY KEY,tile TEXT NOT NULL,road_id TEXT NOT NULL,payload TEXT NOT NULL,UNIQUE(tile,road_id))")
        db.execSQL("CREATE INDEX road_tile ON roads(tile)")
        db.execSQL("CREATE VIRTUAL TABLE road_bounds USING rtree(pk,south,north,west,east)")
        db.execSQL("CREATE TRIGGER road_delete AFTER DELETE ON roads BEGIN DELETE FROM road_bounds WHERE pk=OLD.pk; END")
        db.execSQL("CREATE TABLE public_cameras(tile TEXT NOT NULL,id TEXT NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(tile,id))")
        db.execSQL("CREATE INDEX public_camera_location ON public_cameras(lat,lon)")
        db.execSQL("CREATE TABLE overrides(road TEXT NOT NULL,bearing REAL NOT NULL,mph INTEGER NOT NULL,PRIMARY KEY(road,bearing))")
        db.execSQL("CREATE TABLE boundaries(key TEXT PRIMARY KEY,payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) = Unit
    fun coverage(): Map<RoadTile,Long> = readableDatabase.rawQuery("SELECT id,fetched FROM tiles WHERE complete=1",null).use { c ->
        buildMap { while(c.moveToNext()) put(RoadTile.parse(c.getString(0)),c.getLong(1)) }
    }
    fun replace(data: RoadTileData) {
        require(data.fetchedAt > 0 && data.roads.size <= 30_000)
        require(data.roads.map { it.id }.distinct().size == data.roads.size)
        require(data.cameras.map { it.id }.distinct().size == data.cameras.size)
        require(data.roads.all { r -> r.id.isNotBlank() && r.points.size in 2..20_000 && r.points.all(::validPoint) })
        require(data.cameras.all { it.source == CameraSource.OSM && validPoint(it.point) })
        val payloads = data.roads.map { it to RoadJson.encode(it).toString() }
        val cameraPayloads = data.cameras.map { it to RoadJson.camera(it).toString() }
        val bytes = payloads.sumOf { it.second.toByteArray().size.toLong() } + cameraPayloads.sumOf { it.second.toByteArray().size.toLong() }
        require(bytes <= 8_000_000) { "Tile too large to persist" }
        val db = writableDatabase; db.beginTransaction()
        try {
            deleteTile(db,data.tile.id)
            db.insertOrThrow("tiles",null,ContentValues().apply { put("id",data.tile.id);put("fetched",data.fetchedAt);put("bytes",bytes);put("complete",if(data.complete) 1 else 0) })
            payloads.forEach { (r,json) ->
                val pk = db.insertOrThrow("roads",null,ContentValues().apply { put("tile",data.tile.id);put("road_id",r.id);put("payload",json) })
                db.execSQL("INSERT INTO road_bounds VALUES(?,?,?,?,?)",arrayOf<Any>(pk,r.points.minOf { it.lat },r.points.maxOf { it.lat },r.points.minOf { it.lon },r.points.maxOf { it.lon }))
            }
            cameraPayloads.forEach { (c,json) -> db.insertOrThrow("public_cameras",null,ContentValues().apply {
                put("tile",data.tile.id);put("id",c.id);put("lat",c.point.lat);put("lon",c.point.lon);put("payload",json)
            }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun deleteTile(db: SQLiteDatabase,id: String) {
        db.delete("roads","tile=?",arrayOf(id)); db.delete("public_cameras","tile=?",arrayOf(id));db.delete("tiles","id=?",arrayOf(id))
    }
    fun nearby(point: GeoPoint): LocalRoads {
        val dy=700.0/111195.0; val dx=700.0/(111320.0*cos(Math.toRadians(point.lat)).coerceAtLeast(.1))
        val roads=readableDatabase.rawQuery("SELECT r.road_id,r.payload,t.fetched FROM road_bounds b JOIN roads r ON r.pk=b.pk JOIN tiles t ON t.id=r.tile WHERE b.south<=? AND b.north>=? AND b.west<=? AND b.east>=? ORDER BY t.fetched DESC",
            arrayOf((point.lat+dy).toString(),(point.lat-dy).toString(),(point.lon+dx).toString(),(point.lon-dx).toString())).use { c ->
            val seen=mutableSetOf<String>();buildList { while(c.moveToNext()) if(seen.add(c.getString(0))) add(SavedRoad(RoadJson.decode(JSONObject(c.getString(1))),c.getLong(2))) }
        }
        val cameras=readableDatabase.rawQuery("SELECT c.id,c.payload FROM public_cameras c JOIN tiles t ON t.id=c.tile WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? ORDER BY t.fetched DESC",
            arrayOf((point.lat-dy*2).toString(),(point.lat+dy*2).toString(),(point.lon-dx*2).toString(),(point.lon+dx*2).toString())).use { c ->
            val seen=mutableSetOf<String>();buildList { while(c.moveToNext()) if(seen.add(c.getString(0))) add(RoadJson.decodeCamera(JSONObject(c.getString(1)))) }
        }
        return LocalRoads(roads,cameras)
    }
    /** Evict oldest public tiles only. The caller protects the local 3x3 region, not an unbounded trip. */
    fun cleanup(protected: Set<RoadTile>,maxTiles: Int=160,maxBytes: Long=32_000_000) {
        val db=writableDatabase
        val all=db.rawQuery("SELECT id,bytes FROM tiles ORDER BY fetched ASC",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0) to c.getLong(1)) } }
        var count=all.size;var bytes=all.sumOf { it.second }
        db.beginTransaction()
        try { all.forEach { (id,size) -> if((count>maxTiles || bytes>maxBytes) && RoadTile.parse(id) !in protected) { deleteTile(db,id);count--;bytes-=size } };db.setTransactionSuccessful() }
        finally { db.endTransaction() }
        db.execSQL("PRAGMA incremental_vacuum(2048)")
    }
    data class Override(val road: String,val bearing: Double,val mph: Int)
    fun overrides(): List<Override> = readableDatabase.rawQuery("SELECT road,bearing,mph FROM overrides",null).use { c ->
        buildList { while(c.moveToNext()) add(Override(c.getString(0),c.getDouble(1),c.getInt(2))) }
    }
    fun overrideFor(road: String,bearing: Double?): Int? = selectOverride(overrides(),road,bearing)
    fun setOverride(road: String,bearing: Double,mph: Int?) {
        require(road.isNotBlank() && bearing.isFinite() && bearing in 0.0..<360.0 && (mph==null || mph in 5..100))
        val db=writableDatabase;db.beginTransaction()
        try {
            overrides().filter { it.road==road && Geo.difference(it.bearing,bearing)<45 }.forEach { db.delete("overrides","road=? AND bearing=?",arrayOf(road,it.bearing.toString())) }
            if(mph!=null) db.insertOrThrow("overrides",null,ContentValues().apply { put("road",road);put("bearing",bearing);put("mph",mph) })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun saveBoundary(b: BoundaryCorrection) {
        require(validPoint(b.predicted) && validPoint(b.observed) && b.oldMph in 5..100 && b.newMph in 5..100 && b.fromId!=b.toId)
        val db=writableDatabase;db.beginTransaction()
        try {
            boundaries().filter { it.fromId==b.fromId && it.toId==b.toId && Geo.difference(it.bearing,b.bearing)<45 }.forEach {
                db.delete("boundaries","key=?",arrayOf(boundaryKey(it)))
            }
            db.insertOrThrow("boundaries",null,ContentValues().apply { put("key",boundaryKey(b));put("payload",RoadJson.boundary(b).toString()) })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun boundaryKey(b: BoundaryCorrection)="${b.fromId}|${b.toId}|${b.bearing}"
    fun boundaries(): List<BoundaryCorrection> = readableDatabase.rawQuery("SELECT payload FROM boundaries",null).use { c ->
        buildList { while(c.moveToNext()) add(RoadJson.decodeBoundary(JSONObject(c.getString(0)))) }
    }
    fun resetCorrections() {
        val db=writableDatabase;db.beginTransaction()
        try { db.delete("boundaries",null,null);db.delete("overrides",null,null);db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    companion object {
        fun selectOverride(overrides: List<Override>,road: String,bearing: Double?): Int? = bearing?.let { heading ->
            overrides.filter { it.road==road && Geo.difference(it.bearing,heading)<45 }.minByOrNull { Geo.difference(it.bearing,heading) }?.mph
        }
    }
}

internal object RoadJson {
    fun point(p: GeoPoint)=JSONArray().put(p.lat).put(p.lon)
    fun point(a: JSONArray)=GeoPoint(a.getDouble(0),a.getDouble(1))
    fun encode(r: Road)=JSONObject().put("id",r.id).put("name",r.name?:JSONObject.NULL).put("points",JSONArray().apply { r.points.forEach { put(point(it)) } }).put("tags",JSONObject(r.tags))
    fun decode(j: JSONObject): Road {
        val points=j.getJSONArray("points");val tags=j.getJSONObject("tags")
        return Road(j.getString("id"),j.optString("name").takeUnless { j.isNull("name") },(0 until points.length()).map { point(points.getJSONArray(it)) },tags.keys().asSequence().associateWith { tags.getString(it) })
    }
    fun camera(c: Camera)=JSONObject().put("id",c.id).put("point",point(c.point)).put("type",c.type.name).put("direction",c.direction?:JSONObject.NULL).put("mph",c.enforcedMph?:JSONObject.NULL).put("updated",c.updatedAtMs)
    fun decodeCamera(j: JSONObject)=Camera(j.getString("id"),point(j.getJSONArray("point")),CameraType.valueOf(j.getString("type")),CameraSource.OSM,
        if(j.isNull("direction")) null else j.getDouble("direction"),if(j.isNull("mph")) null else j.getInt("mph"),updatedAtMs=j.getLong("updated"))
    fun boundary(b: BoundaryCorrection)=JSONObject().put("from",b.fromId).put("to",b.toId).put("old",b.oldMph).put("new",b.newMph).put("predicted",point(b.predicted)).put("observed",point(b.observed))
        .put("bearing",b.bearing).put("pa",b.predictedAccuracy).put("oa",b.observedAccuracy).put("confidence",b.confidence).put("distance",b.matchDistance).put("at",b.recordedAt)
    fun decodeBoundary(j: JSONObject)=BoundaryCorrection(j.getString("from"),j.getString("to"),j.getInt("old"),j.getInt("new"),point(j.getJSONArray("predicted")),point(j.getJSONArray("observed")),
        j.getDouble("bearing"),j.getDouble("pa"),j.getDouble("oa"),j.getDouble("confidence"),j.getDouble("distance"),j.getLong("at"))
}

/** Load and validate before entering the replacement transaction. Exceptions leave the active rows alone. */
class RoadCacheUpdater(private val db: RoadDb) {
    fun refresh(tile: RoadTile,load: (RoadTile) -> RoadTileData): RoadTileData {
        val fresh=load(tile)
        require(fresh.tile==tile && fresh.complete) { "Wrong or incomplete replacement tile" }
        db.replace(fresh)
        return fresh
    }
}
