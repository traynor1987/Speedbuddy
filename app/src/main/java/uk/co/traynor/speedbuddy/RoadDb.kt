package uk.co.traynor.speedbuddy

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos

/** Public tile storage is independent of owner cameras, settings and local corrections. */
class RoadDb(context: Context,name: String = "roads.db") : SQLiteOpenHelper(context,name,null,4) {
    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA auto_vacuum=INCREMENTAL") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tiles(id TEXT PRIMARY KEY,fetched INTEGER NOT NULL,bytes INTEGER NOT NULL,complete INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE roads(pk INTEGER PRIMARY KEY,tile TEXT NOT NULL,road_id TEXT NOT NULL,payload TEXT NOT NULL,UNIQUE(tile,road_id))")
        db.execSQL("CREATE INDEX road_tile ON roads(tile)")
        db.execSQL("CREATE TABLE road_cells(y INTEGER NOT NULL,x INTEGER NOT NULL,pk INTEGER NOT NULL,PRIMARY KEY(y,x,pk))")
        db.execSQL("CREATE INDEX road_cells_pk ON road_cells(pk)")
        db.execSQL("CREATE TRIGGER road_delete AFTER DELETE ON roads BEGIN DELETE FROM road_cells WHERE pk=OLD.pk; END")
        db.execSQL("CREATE TABLE public_cameras(tile TEXT NOT NULL,id TEXT NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(tile,id))")
        db.execSQL("CREATE INDEX public_camera_location ON public_cameras(lat,lon)")
        db.execSQL("CREATE TABLE overrides(road TEXT NOT NULL,bearing REAL NOT NULL,mph INTEGER NOT NULL,payload TEXT,PRIMARY KEY(road,bearing))")
        createSections(db)
        db.execSQL("CREATE TABLE boundaries(key TEXT PRIMARY KEY,payload TEXT NOT NULL)")
        createLearning(db)
    }
    private fun createLearning(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE boundary_observations(key TEXT PRIMARY KEY,payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE limit_diagnostics(id INTEGER PRIMARY KEY AUTOINCREMENT,payload TEXT NOT NULL)")
    }
    private fun createSections(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE average_sections(tile TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(tile,id))")
    }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {
        if(oldVersion<4) createLearning(db)
        if(oldVersion<3) createSections(db)
        if(oldVersion<2) db.execSQL("ALTER TABLE overrides ADD COLUMN payload TEXT")
    }
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
        val sectionPayloads=data.averageSections.map { it to RoadJson.section(it).toString() }
        val cameraPayloads = data.cameras.map { it to RoadJson.camera(it).toString() }
        val bytes = sectionPayloads.sumOf { it.second.toByteArray().size.toLong() } + payloads.sumOf { it.second.toByteArray().size.toLong() } + cameraPayloads.sumOf { it.second.toByteArray().size.toLong() }
        require(bytes <= 8_000_000) { "Tile too large to persist" }
        val db = writableDatabase; db.beginTransaction()
        try {
            deleteTile(db,data.tile.id)
            db.insertOrThrow("tiles",null,ContentValues().apply { put("id",data.tile.id);put("fetched",data.fetchedAt);put("bytes",bytes);put("complete",if(data.complete) 1 else 0) })
            payloads.forEach { (r,json) ->
                val pk = db.insertOrThrow("roads",null,ContentValues().apply { put("tile",data.tile.id);put("road_id",r.id);put("payload",json) })
                RoadCells.forRoad(r.points,data.tile).forEach { (y,x) -> db.execSQL("INSERT INTO road_cells VALUES(?,?,?)",arrayOf<Any>(y,x,pk)) }
            }
            cameraPayloads.forEach { (c,json) -> db.insertOrThrow("public_cameras",null,ContentValues().apply {
                put("tile",data.tile.id);put("id",c.id);put("lat",c.point.lat);put("lon",c.point.lon);put("payload",json)
            }) }
            sectionPayloads.forEach { (section,json) -> db.insertOrThrow("average_sections",null,ContentValues().apply {
                put("tile",data.tile.id);put("id",section.id);put("payload",json)
            }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun deleteTile(db: SQLiteDatabase,id: String) {
        db.delete("average_sections","tile=?",arrayOf(id));db.delete("roads","tile=?",arrayOf(id)); db.delete("public_cameras","tile=?",arrayOf(id));db.delete("tiles","id=?",arrayOf(id))
    }
    fun nearby(point: GeoPoint,radiusM: Double=700.0): LocalRoads {
        val dy=radiusM/111195.0; val dx=radiusM/(111320.0*cos(Math.toRadians(point.lat)).coerceAtLeast(.1))
        val low=RoadCells.at(GeoPoint(point.lat-dy,point.lon-dx));val high=RoadCells.at(GeoPoint(point.lat+dy,point.lon+dx))
        val roads=readableDatabase.rawQuery("SELECT r.road_id,r.payload,t.fetched FROM roads r JOIN tiles t ON t.id=r.tile WHERE r.pk IN (SELECT pk FROM road_cells WHERE y BETWEEN ? AND ? AND x BETWEEN ? AND ?) ORDER BY t.fetched DESC",
            arrayOf(low.first.toString(),high.first.toString(),low.second.toString(),high.second.toString())).use { c ->
            val seen=mutableSetOf<String>();buildList { while(c.moveToNext()) if(seen.add(c.getString(0))) add(SavedRoad(RoadJson.decode(JSONObject(c.getString(1))),c.getLong(2))) }
        }
        val cameras=readableDatabase.rawQuery("SELECT c.id,c.payload FROM public_cameras c JOIN tiles t ON t.id=c.tile WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? ORDER BY t.fetched DESC",
            arrayOf((point.lat-dy*2).toString(),(point.lat+dy*2).toString(),(point.lon-dx*2).toString(),(point.lon+dx*2).toString())).use { c ->
            val seen=mutableSetOf<String>();buildList { while(c.moveToNext()) if(seen.add(c.getString(0))) add(RoadJson.decodeCamera(JSONObject(c.getString(1)))) }
        }
        val sections=readableDatabase.rawQuery("SELECT a.id,a.payload FROM average_sections a JOIN tiles t ON a.tile=t.id ORDER BY t.fetched DESC",null).use { c ->
            val seen=mutableSetOf<String>();buildList { while(c.moveToNext()) if(seen.add(c.getString(0))) {
                val section=RoadJson.decodeSection(JSONObject(c.getString(1)))
                if(section.wayIds.any { id -> roads.any { it.road.id==id } }) add(section)
            } }
        }
        return LocalRoads(roads,cameras,sections)
    }
    /** Map extracts are partial coverage. Never erase a complete tile with a small viewport response. */
    fun mergeMapSnapshot(snapshot: OsmSnapshot) {
        val centerTile=RoadTile.at(snapshot.center)
        val tileList=buildList { for(y in centerTile.y-1..centerTile.y+1) for(x in centerTile.x-1..centerTile.x+1) add(RoadTile(y,x)) }
        val db=writableDatabase
        db.beginTransaction()
        try {
            for(tile in tileList) {
                val viewportRoads=snapshot.roads.filter { RoadCells.forRoad(it.points,tile).isNotEmpty() }
                val viewportCameras=snapshot.cameras.filter { RoadTile.at(it.point)==tile }
                if(viewportRoads.isEmpty() && viewportCameras.isEmpty()) continue
                val metadata=db.rawQuery("SELECT fetched,complete FROM tiles WHERE id=?",arrayOf(tile.id)).use { c ->
                    if(c.moveToFirst()) c.getLong(0) to (c.getInt(1)==1) else null
                }
                // Older manual extracts cannot replace newer complete road data.
                if(metadata?.second==true && metadata.first>=snapshot.fetchedAt) continue
                val oldRoads=db.rawQuery("SELECT payload FROM roads WHERE tile=?",arrayOf(tile.id)).use { c ->
                    buildList { while(c.moveToNext()) add(RoadJson.decode(JSONObject(c.getString(0)))) }
                }
                val oldCameras=db.rawQuery("SELECT payload FROM public_cameras WHERE tile=?",arrayOf(tile.id)).use { c ->
                    buildList { while(c.moveToNext()) add(RoadJson.decodeCamera(JSONObject(c.getString(0)))) }
                }
                val oldSections=db.rawQuery("SELECT payload FROM average_sections WHERE tile=?",arrayOf(tile.id)).use { c ->
                    buildList { while(c.moveToNext()) add(RoadJson.decodeSection(JSONObject(c.getString(0)))) }
                }
                val oldFirst=metadata!=null && metadata.first>=snapshot.fetchedAt
                replace(RoadTileData(tile,if(metadata?.second==true) metadata.first else maxOf(metadata?.first ?: 0,snapshot.fetchedAt),
                    (if(oldFirst) oldRoads+viewportRoads else viewportRoads+oldRoads).distinctBy { it.id },
                    (if(oldFirst) oldCameras+viewportCameras else viewportCameras+oldCameras).distinctBy { it.id },
                    complete=metadata?.second==true,
                    averageSections=(if(oldFirst) oldSections+snapshot.averageSections else snapshot.averageSections+oldSections).distinctBy { it.id }))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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
    data class Override(val road: String,val bearing: Double,val mph: Int,val sourceMph: Int? = null,
        val point: GeoPoint? = null,val recordedAt: Long = 0,val accuracy: Double? = null)
    fun overrides(): List<Override> = readableDatabase.rawQuery("SELECT road,bearing,mph,payload FROM overrides",null).use { c ->
        buildList { while(c.moveToNext()) add(if(c.isNull(3)) Override(c.getString(0),c.getDouble(1),c.getInt(2)) else RoadJson.decodeOverride(JSONObject(c.getString(3)))) }
    }
    fun overrideFor(road: String,bearing: Double?): Int? = selectOverride(overrides(),road,bearing)
    fun setOverride(road: String,bearing: Double,mph: Int?) {
        if(mph!=null) { saveOverride(Override(road,bearing,mph));return }
        require(road.isNotBlank() && bearing.isFinite() && bearing in 0.0..<360.0)
        val db=writableDatabase;db.beginTransaction()
        try {
            overrides().filter { it.road==road && Geo.difference(it.bearing,bearing)<45 }.forEach { db.delete("overrides","road=? AND bearing=?",arrayOf(road,it.bearing.toString())) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun saveOverride(row: Override) {
        require(row.road.isNotBlank() && row.road.length<=100 && row.bearing.isFinite() && row.bearing in 0.0..<360.0 && OwnerLimit.valid(row.mph))
        require(row.sourceMph==null || row.sourceMph in 5..100)
        require(row.point==null || validPoint(row.point))
        require(row.recordedAt>=0 && (row.accuracy==null || row.accuracy.isFinite() && row.accuracy in 0.0..20.0))
        val db=writableDatabase;db.beginTransaction()
        try {
            overrides().filter { it.road==row.road && Geo.difference(it.bearing,row.bearing)<45 }.forEach {
                db.delete("overrides","road=? AND bearing=?",arrayOf(it.road,it.bearing.toString()))
            }
            db.insertOrThrow("overrides",null,ContentValues().apply {
                put("road",row.road);put("bearing",row.bearing);put("mph",row.mph);put("payload",RoadJson.override(row).toString())
            })
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
    fun observations(): List<BoundaryObservation> = readableDatabase.rawQuery("SELECT payload FROM boundary_observations",null).use { c ->
        buildList { while(c.moveToNext()) add(RoadJson.decodeObservation(JSONObject(c.getString(0)))) }
    }
    private fun observationKey(o: BoundaryObservation)="${o.from.id}|${o.to.id}|${o.bearing}"
    private fun saveObservation(o: BoundaryObservation) {
        o.validate()
        // Remove expired pending evidence; completed boundaries are never pruned here.
        observations().filter { o.recordedAt-it.recordedAt>120_000 }.forEach {
            writableDatabase.delete("boundary_observations","key=?",arrayOf(observationKey(it)))
        }
        observations().filter { it.from.id==o.from.id && it.to.id==o.to.id && Geo.difference(it.bearing,o.bearing)<45 }.forEach {
            writableDatabase.delete("boundary_observations","key=?",arrayOf(observationKey(it)))
        }
        writableDatabase.insertOrThrow("boundary_observations",null,ContentValues().apply {
            put("key",observationKey(o));put("payload",RoadJson.observation(o).toString())
        })
    }
    /** Persist evidence and classification together before activating any live selection. */
    fun saveSelection(plan: LimitSelectionPlan,diagnostic: String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            plan.override?.let(::saveOverride);plan.observation?.let(::saveObservation)
            val retired=plan.boundary?.let { b -> overrides().filter {
                it.road in b.viaIds+b.toId && Geo.difference(it.bearing,b.bearing)<45 &&
                    (it.point==null || Geo.distance(it.point,b.observed)<=600)
            } } ?: emptyList()
            plan.boundary?.let { b ->
                // The owner's newer local boundary replaces the candidate's whole-segment tap.
                retired.forEach { setOverride(it.road,it.bearing,null) };saveBoundary(b)
            }
            plan.consumed?.let { db.delete("boundary_observations","key=?",arrayOf(observationKey(it))) }
            recordDiagnostic(JSONObject(diagnostic).put("retiredSegmentOverrides",JSONArray(retired.map(RoadJson::override))).toString())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun recordDiagnostic(payload: String) {
        require(payload.length<=32_000);JSONObject(payload)
        val db=writableDatabase
        db.insertOrThrow("limit_diagnostics",null,ContentValues().apply { put("payload",payload) })
        db.execSQL("DELETE FROM limit_diagnostics WHERE id NOT IN (SELECT id FROM limit_diagnostics ORDER BY id DESC LIMIT 500)")
    }
    fun diagnostics(): List<String> = readableDatabase.rawQuery("SELECT payload FROM limit_diagnostics ORDER BY id DESC",null).use { c ->
        buildList { while(c.moveToNext()) add(c.getString(0)) }
    }
    fun deleteOwnerCorrections(road: String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            db.delete("overrides","road=?",arrayOf(road))
            boundaries().filter { it.fromId==road || it.toId==road }.forEach {
                db.delete("boundaries","key=?",arrayOf(boundaryKey(it)))
            }
            observations().filter { it.from.id==road || it.to.id==road }.forEach {
                db.delete("boundary_observations","key=?",arrayOf(observationKey(it)))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        OwnerDataRevision.roads++
    }
    fun resetCorrections() {
        val db=writableDatabase;db.beginTransaction()
        try { db.delete("boundary_observations",null,null);db.delete("boundaries",null,null);db.delete("overrides",null,null);db.setTransactionSuccessful() } finally { db.endTransaction() }
        OwnerDataRevision.roads++
    }
    fun mergeOwnerCorrections(overrides: List<Override>, boundaries: List<BoundaryCorrection>,observations: List<BoundaryObservation> = emptyList()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            overrides.forEach(::saveOverride)
            boundaries.forEach(::saveBoundary)
            observations.forEach(::saveObservation)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    companion object {
        fun selectOverride(overrides: List<Override>,road: String,bearing: Double?): Int? = bearing?.let { heading ->
            overrides.filter { it.road==road && Geo.difference(it.bearing,heading)<45 }.minByOrNull { Geo.difference(it.bearing,heading) }?.mph
        }
    }
}

internal object RoadJson {
    fun override(o: RoadDb.Override)=JSONObject().put("road",o.road).put("bearing",o.bearing).put("mph",o.mph)
        .put("source",o.sourceMph?:JSONObject.NULL).put("point",o.point?.let(::point)?:JSONObject.NULL)
        .put("at",o.recordedAt).put("accuracy",o.accuracy?:JSONObject.NULL)
    fun decodeOverride(j: JSONObject)=RoadDb.Override(j.getString("road"),j.getDouble("bearing"),j.getInt("mph"),
        if(j.isNull("source")) null else j.getInt("source"),if(j.isNull("point")) null else point(j.getJSONArray("point")),
        j.optLong("at",0),if(j.isNull("accuracy")) null else j.getDouble("accuracy"))
    fun point(p: GeoPoint)=JSONArray().put(p.lat).put(p.lon)
    fun point(a: JSONArray)=GeoPoint(a.getDouble(0),a.getDouble(1))
    fun encode(r: Road)=JSONObject().put("id",r.id).put("name",r.name?:JSONObject.NULL).put("points",JSONArray().apply { r.points.forEach { put(point(it)) } }).put("tags",JSONObject(r.tags))
    fun decode(j: JSONObject): Road {
        val points=j.getJSONArray("points");val tags=j.getJSONObject("tags")
        return Road(j.getString("id"),j.optString("name").takeUnless { j.isNull("name") },(0 until points.length()).map { point(points.getJSONArray(it)) },tags.keys().asSequence().associateWith { tags.getString(it) })
    }
    fun camera(c: Camera)=JSONObject().put("id",c.id).put("point",point(c.point)).put("type",c.type.name).put("direction",c.direction?:JSONObject.NULL).put("mph",c.enforcedMph?:JSONObject.NULL).put("updated",c.updatedAtMs).put("bidirectional",c.bidirectional)
    fun decodeCamera(j: JSONObject)=Camera(j.getString("id"),point(j.getJSONArray("point")),CameraType.valueOf(j.getString("type")),CameraSource.OSM,
        if(j.isNull("direction")) null else j.getDouble("direction"),if(j.isNull("mph")) null else j.getInt("mph"),updatedAtMs=j.getLong("updated"),bidirectional=j.optBoolean("bidirectional",false))
    fun section(s: AverageSpeedSection)=JSONObject().put("id",s.id).put("points",JSONArray(s.points.map(::point)))
        .put("ways",JSONArray(s.wayIds.sorted())).put("mph",s.mph?:JSONObject.NULL)
    fun decodeSection(j: JSONObject): AverageSpeedSection {
        val p=j.getJSONArray("points");val w=j.getJSONArray("ways")
        return AverageSpeedSection(j.getString("id"),(0 until p.length()).map { point(p.getJSONArray(it)) },
            (0 until w.length()).mapTo(hashSetOf()) { w.getString(it) },if(j.isNull("mph")) null else j.getInt("mph"))
    }
    fun boundary(b: BoundaryCorrection)=JSONObject().put("from",b.fromId).put("to",b.toId).put("old",b.oldMph).put("new",b.newMph).put("predicted",point(b.predicted)).put("observed",point(b.observed))
        .put("bearing",b.bearing).put("pa",b.predictedAccuracy).put("oa",b.observedAccuracy).put("confidence",b.confidence).put("distance",b.matchDistance).put("at",b.recordedAt)
        .put("via",JSONArray(b.viaIds)).put("still",b.stillPoint?.let(::point) ?: JSONObject.NULL)
    fun decodeBoundary(j: JSONObject)=BoundaryCorrection(j.getString("from"),j.getString("to"),j.getInt("old"),j.getInt("new"),point(j.getJSONArray("predicted")),point(j.getJSONArray("observed")),
        j.getDouble("bearing"),j.getDouble("pa"),j.getDouble("oa"),j.getDouble("confidence"),j.getDouble("distance"),j.getLong("at"),
        j.optJSONArray("via")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        if(j.isNull("still")) null else point(j.getJSONArray("still")))
    fun observation(o: BoundaryObservation)=JSONObject().put("from",encode(o.from)).put("to",encode(o.to))
        .put("old",o.oldMph).put("new",o.newMph).put("predicted",point(o.predicted)).put("still",point(o.stillPoint))
        .put("bearing",o.bearing).put("pa",o.predictedAccuracy).put("accuracy",o.accuracy).put("at",o.recordedAt)
    fun decodeObservation(j: JSONObject)=BoundaryObservation(decode(j.getJSONObject("from")),decode(j.getJSONObject("to")),
        j.getInt("old"),j.getInt("new"),point(j.getJSONArray("predicted")),point(j.getJSONArray("still")),
        j.getDouble("bearing"),j.getDouble("pa"),j.getDouble("accuracy"),j.getLong("at"))
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
