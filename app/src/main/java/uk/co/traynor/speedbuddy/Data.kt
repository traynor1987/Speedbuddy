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
object OwnerDataRevision { @Volatile var roads: Long = 0L; @Volatile var cameras: Long = 0L }

data class CameraCorrection(val id: String, val source: CameraSource, val point: GeoPoint,
    val type: CameraType, val direction: Double?, val note: String?,
    val enforcedMph: Int? = null, val sourcePoint: GeoPoint? = null, val updatedAtMs: Long = 0L,
    val bidirectional: Boolean = false) {
    fun apply(camera: Camera): Camera = if (camera.id == id && camera.source == source)
        camera.copy(point = point, type = type, direction = direction, note = note,
            enforcedMph = enforcedMph, updatedAtMs = updatedAtMs,
            bidirectional = bidirectional,locallyCorrected=true) else camera
}

class CameraDb(context: Context, databaseName: String = "cameras.db") : SQLiteOpenHelper(context, databaseName, null, 10), CameraRepository {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cameras(id TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, type TEXT NOT NULL, direction REAL, mph INTEGER, note TEXT, updated INTEGER NOT NULL, bidirectional INTEGER NOT NULL DEFAULT 0)")
        createImported(db)
        createCorrections(db)
        createSuppressed(db)
        createImportStatus(db)
        createAliases(db)
        MobileReportStore.createTable(db)
        JunctionStore.createTables(db)
        db.execSQL("CREATE INDEX owner_location ON cameras(lat,lon)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createImported(db)
        if (oldVersion < 3) createCorrections(db)
        if (oldVersion in 3 until 4) {
            db.execSQL("ALTER TABLE camera_corrections ADD COLUMN mph INTEGER")
            db.execSQL("ALTER TABLE camera_corrections ADD COLUMN source_lat REAL")
            db.execSQL("ALTER TABLE camera_corrections ADD COLUMN source_lon REAL")
            db.execSQL("ALTER TABLE camera_corrections ADD COLUMN updated INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) createSuppressed(db)
        if (oldVersion in 3 until 5) {
            db.execSQL("ALTER TABLE road_limits ADD COLUMN kind TEXT NOT NULL DEFAULT 'NUMERIC'")
            db.execSQL("ALTER TABLE road_limits ADD COLUMN source_value TEXT")
            db.execSQL("ALTER TABLE road_limits ADD COLUMN updated INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 6) createImportStatus(db)
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE cameras ADD COLUMN bidirectional INTEGER NOT NULL DEFAULT 0")
            if (oldVersion >= 3) db.execSQL("ALTER TABLE camera_corrections ADD COLUMN bidirectional INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 8) {
            createAliases(db)
            db.execSQL("CREATE INDEX IF NOT EXISTS owner_location ON cameras(lat,lon)")
        }
        if (oldVersion < 9 && newVersion >= 9) MobileReportStore.createTable(db)
        if (oldVersion < 10 && newVersion >= 10) JunctionStore.createTables(db)
    }
    private fun createAliases(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE camera_aliases(a TEXT NOT NULL,b TEXT NOT NULL,PRIMARY KEY(a,b))")
    }
    private fun createImportStatus(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE import_status(attempted INTEGER NOT NULL, failure TEXT)")
    }
    private fun createSuppressed(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE suppressed_cameras(id TEXT PRIMARY KEY, source TEXT NOT NULL, updated INTEGER NOT NULL)")
    }
    private fun createCorrections(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE camera_corrections(id TEXT PRIMARY KEY, source TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, type TEXT NOT NULL, direction REAL, note TEXT, mph INTEGER, source_lat REAL, source_lon REAL, updated INTEGER NOT NULL DEFAULT 0, bidirectional INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE road_limits(id TEXT PRIMARY KEY, mph INTEGER NOT NULL, kind TEXT NOT NULL DEFAULT 'NUMERIC', source_value TEXT, updated INTEGER NOT NULL DEFAULT 0)")
    }
    fun cameraCorrections(): List<CameraCorrection> = readableDatabase.rawQuery(
        "SELECT id,source,lat,lon,type,direction,note,mph,source_lat,source_lon,updated,bidirectional FROM camera_corrections", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(CameraCorrection(cursor.getString(0), CameraSource.valueOf(cursor.getString(1)),
            GeoPoint(cursor.getDouble(2), cursor.getDouble(3)), CameraType.valueOf(cursor.getString(4)),
            if (cursor.isNull(5)) null else cursor.getDouble(5), cursor.getString(6),
            if (cursor.isNull(7)) null else cursor.getInt(7),
            if (cursor.isNull(8) || cursor.isNull(9)) null else GeoPoint(cursor.getDouble(8), cursor.getDouble(9)),
            cursor.getLong(10), cursor.getInt(11) != 0)) }
    }
    fun suppressedCameraIds(): Set<String> = readableDatabase.rawQuery("SELECT id FROM suppressed_cameras", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }
    fun suppressCamera(id: String, source: CameraSource) {
        require(source != CameraSource.USER && id.isNotBlank())
        writableDatabase.insertWithOnConflict("suppressed_cameras", null, ContentValues().apply {
            put("id", id); put("source", source.name); put("updated", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        OwnerDataRevision.cameras++
    }
    fun unsuppressCamera(id: String) { writableDatabase.delete("suppressed_cameras", "id=?", arrayOf(id)); OwnerDataRevision.cameras++ }
    fun resetCameraOverrides() {
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete("camera_corrections", null, null)
            database.delete("suppressed_cameras", null, null)
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        OwnerDataRevision.cameras++
    }
    fun mergeSuppressed(ids: Set<String>) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            ids.forEach { id -> suppressCamera(id,
                if (id.startsWith("lufop:")) CameraSource.LUFOP else CameraSource.OSM) }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }
    fun applyCameraCorrections(cameras: List<Camera>): List<Camera> {
        return CameraLayers.merge(cameras, emptyList(), cameraCorrections(), suppressedCameraIds())
    }
    fun effectiveCameras(source: List<Camera>, owner: List<Camera> = userCameras()): List<Camera> =
        CameraLayers.merge(source, owner, cameraCorrections(), suppressedCameraIds(), aliasLinks())
    fun aliasLinks(): List<Pair<String, String>> = readableDatabase.rawQuery("SELECT a,b FROM camera_aliases", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
    }
    fun restoreHiddenCamera(id: String) {
        val parent=mutableMapOf<String,String>()
        fun root(value: String): String { var result=value;while(parent[result]!=null && parent[result]!=result) result=parent.getValue(result);return result }
        aliasLinks().forEach { (a,b)->val ra=root(a);val rb=root(b);if(ra!=rb) parent[ra]=rb }
        val group=root(id)
        writableDatabase.beginTransaction()
        try { suppressedCameraIds().filter { root(it)==group }.forEach(::unsuppressCamera);writableDatabase.setTransactionSuccessful() }
        finally { writableDatabase.endTransaction() }
    }
    fun hideEffectiveCamera(camera: Camera) {
        require(camera.source!=CameraSource.USER)
        val database = writableDatabase
        database.beginTransaction()
        try {
            (camera.aliasIds + camera.id).forEach { id -> suppressCamera(id,
                if (id.startsWith("lufop:")) CameraSource.LUFOP else CameraSource.OSM) }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }
    /** One bounded, shared effective repository for both Map and Drive. */
    fun effectiveInBounds(south: Double, west: Double, north: Double, east: Double,
        public: List<Camera>): List<Camera> {
        val raw = importedInBounds(south, west, north, east) + public
        val links = CameraLayers.aliases(raw)
        links.forEach { (a,b) -> writableDatabase.insertWithOnConflict("camera_aliases", null,
            ContentValues().apply { put("a",a); put("b",b) }, SQLiteDatabase.CONFLICT_IGNORE) }
        val corrections = cameraCorrections()
        val moved = corrections.filter { it.point.lat in south..north && it.point.lon in west..east }
            .filter { c -> raw.none { it.id == c.id } }.mapNotNull { c ->
                (c.sourcePoint ?: if (c.source == CameraSource.LUFOP) importedPoint(c.id) else null)?.let {
                    Camera(c.id, it, c.type, c.source)
                }
            }
        val owner = readableDatabase.rawQuery("SELECT id,lat,lon,type,direction,mph,note,updated,bidirectional FROM cameras WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            arrayOf(south.toString(),north.toString(),west.toString(),east.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Camera(cursor.getString(0), GeoPoint(cursor.getDouble(1),cursor.getDouble(2)),
                CameraType.valueOf(cursor.getString(3)),CameraSource.USER,
                if(cursor.isNull(4)) null else cursor.getDouble(4),if(cursor.isNull(5)) null else cursor.getInt(5),
                cursor.getString(6),cursor.getLong(7),cursor.getInt(8)!=0)) }
        }
        return CameraLayers.merge(raw + moved, JunctionStore(this).attach(owner), corrections, suppressedCameraIds(), aliasLinks()).filter {
            it.point.lat in south..north && it.point.lon in west..east
        } + MobileReportStore(this).activeInBounds(south,west,north,east).map(MobileReport::asCamera)
    }
    fun restoreOwnerData(backup: OwnerBackup, prefs: android.content.SharedPreferences) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val replacingIds=backup.cameras.map { it.id }.toSet()
            backup.junctions.forEach { JunctionStore(this).restore(it,replacingIds) }
            merge(backup.cameras); mergeCorrections(backup.corrections, backup.roadLimits)
            mergeSuppressed(backup.suppressedCameraIds); mergeRoadCorrections(backup.roadCorrections)
            backup.aliases.forEach { (a,b)->database.insertOrThrow("camera_aliases",null,ContentValues().apply { put("a",a);put("b",b) }.also {
                database.delete("camera_aliases","a=? AND b=?",arrayOf(a,b))
            }) }
            OwnerBackupCodec.applySettings(backup.settings, prefs)
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }
    fun saveCameraCorrection(value: CameraCorrection) {
        require(value.type != CameraType.MOBILE && value.source != CameraSource.USER && value.point.lat in -90.0..90.0 && value.point.lon in -180.0..180.0 &&
            (value.direction == null || value.direction.isFinite() && value.direction >= 0.0 && value.direction < 360.0) &&
            (!value.bidirectional || value.direction != null) &&
            (value.enforcedMph == null || value.enforcedMph in 5..130))
        check(writableDatabase.insertWithOnConflict("camera_corrections", null, ContentValues().apply {
            put("id", value.id); put("source", value.source.name); put("lat", value.point.lat); put("lon", value.point.lon)
            put("type", value.type.name); put("direction", value.direction); put("note", value.note)
            put("mph", value.enforcedMph); put("source_lat", value.sourcePoint?.lat)
            put("source_lon", value.sourcePoint?.lon)
            put("updated", value.updatedAtMs.takeIf { it > 0 } ?: System.currentTimeMillis())
            put("bidirectional", if (value.bidirectional) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE) >= 0) { "Could not save camera correction" }
        OwnerDataRevision.cameras++
    }
    fun deleteCameraCorrection(id: String) { writableDatabase.delete("camera_corrections", "id=?", arrayOf(id)); OwnerDataRevision.cameras++ }
    fun roadCorrections(): List<RoadLimitCorrection> = readableDatabase.rawQuery(
        "SELECT id,mph,kind,source_value,updated FROM road_limits", null).use { c ->
        buildList { while (c.moveToNext()) {
            val kind = RoadLimitKind.valueOf(c.getString(2))
            add(RoadLimitCorrection(c.getString(0), kind,
                if (kind == RoadLimitKind.UNKNOWN) null else c.getInt(1), c.getString(3), c.getLong(4)))
        } }
    }
    fun roadLimits(): Map<String, Int> = roadCorrections().filter { it.mph != null }.associate { it.id to it.mph!! }
    fun roadCorrection(id: String): RoadLimitCorrection? = readableDatabase.rawQuery(
        "SELECT mph,kind,source_value,updated FROM road_limits WHERE id=?", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null else {
            val kind = RoadLimitKind.valueOf(c.getString(1))
            RoadLimitCorrection(id, kind, if (kind == RoadLimitKind.UNKNOWN) null else c.getInt(0),
                c.getString(2), c.getLong(3))
        }
    }
    fun roadLimit(id: String): Int? = roadCorrection(id)?.mph
    fun saveRoadLimit(id: String, mph: Int) {
        saveRoadCorrection(RoadLimitCorrection(id, RoadLimitKind.NUMERIC, mph, null, System.currentTimeMillis()))
    }
    fun saveRoadCorrection(value: RoadLimitCorrection) {
        check(writableDatabase.insertWithOnConflict("road_limits", null, ContentValues().apply {
            put("id", value.id); put("mph", value.mph ?: 0); put("kind", value.kind.name)
            put("source_value", value.sourceValue); put("updated", value.updatedAtMs)
        }, SQLiteDatabase.CONFLICT_REPLACE) >= 0) { "Could not save road correction" }
        OwnerDataRevision.roads++
    }
    fun deleteRoadLimit(id: String) {
        writableDatabase.delete("road_limits", "id=?", arrayOf(id)); OwnerDataRevision.roads++
    }
    fun resetRoadLimits() { writableDatabase.delete("road_limits", null, null); OwnerDataRevision.roads++ }
    fun mergeCorrections(cameras: List<CameraCorrection>, roads: Map<String, Int>) {
        val database = writableDatabase
        database.beginTransaction()
        try { cameras.forEach(::saveCameraCorrection); roads.forEach(::saveRoadLimit)
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }
    fun mergeRoadCorrections(corrections: List<RoadLimitCorrection>) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            corrections.forEach(::saveRoadCorrection)
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }
    private fun createImported(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE imported_cameras(id TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, type TEXT NOT NULL)")
        db.execSQL("CREATE INDEX imported_location ON imported_cameras(lat,lon)")
        db.execSQL("CREATE TABLE imported_info(imported_at INTEGER NOT NULL, source_date TEXT NOT NULL, count INTEGER NOT NULL)")
    }
    data class ImportedInfo(val importedAtMs: Long, val sourceDate: String, val count: Int)
    data class ImportStatus(val attemptedAtMs: Long, val failure: String?)
    fun importStatus(): ImportStatus? = readableDatabase.rawQuery("SELECT attempted,failure FROM import_status LIMIT 1", null).use { c ->
        if (c.moveToFirst()) ImportStatus(c.getLong(0), c.getString(1)) else null
    }
    fun recordImportFailure(reason: String) {
        writableDatabase.delete("import_status", null, null)
        writableDatabase.insertOrThrow("import_status", null, ContentValues().apply {
            put("attempted", System.currentTimeMillis()); put("failure", reason.take(160))
        })
    }
    fun seedImportedIfEmpty(cameras: List<Camera>,sourceDate: String) {
        val database=writableDatabase;database.beginTransaction()
        try { if(importedInfo()==null) replaceImported(cameras,sourceDate);database.setTransactionSuccessful() }
        finally { database.endTransaction() }
    }
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
    fun importedInBounds(south: Double, west: Double, north: Double, east: Double): List<Camera> = readableDatabase.rawQuery(
        "SELECT id,lat,lon,type FROM imported_cameras WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT 10000",
        arrayOf(south.toString(), north.toString(), west.toString(), east.toString())).use { cursor ->
        buildList { while (cursor.moveToNext()) add(Camera(cursor.getString(0), GeoPoint(cursor.getDouble(1), cursor.getDouble(2)),
            CameraType.valueOf(cursor.getString(3)), CameraSource.LUFOP)) }
    }
    fun ownerCameraCount(): Int = readableDatabase.rawQuery("SELECT count(*) FROM cameras",null).use { it.moveToFirst();it.getInt(0) }
    fun importedPoint(id: String): GeoPoint? = readableDatabase.rawQuery(
        "SELECT lat,lon FROM imported_cameras WHERE id=?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) GeoPoint(c.getDouble(0), c.getDouble(1)) else null
    }
    /** Replaces only the downloaded layer. Owner cameras and OSM cache remain untouched. */
    fun replaceImported(cameras: List<Camera>, sourceDate: String): Int {
        require(cameras.isNotEmpty() && cameras.all { it.source == CameraSource.LUFOP && it.type != CameraType.MOBILE && it.mobileReport==null })
        val database = writableDatabase
        database.beginTransaction()
        try {
            // Preserve local choices when a source coordinate changes and a unique same-type
            // replacement lies nearby. Ambiguous matches remain orphaned rather than guessed.
            val newIds = cameras.mapTo(hashSetOf()) { it.id }
            val ownerIds = mutableSetOf<String>()
            database.rawQuery("SELECT id FROM camera_corrections WHERE source='LUFOP' UNION SELECT id FROM suppressed_cameras WHERE source='LUFOP'", null).use { c ->
                while (c.moveToNext()) ownerIds += c.getString(0)
            }
            ownerIds.filterNot { it in newIds }.forEach { oldId ->
                database.rawQuery("SELECT lat,lon,type FROM imported_cameras WHERE id=?", arrayOf(oldId)).use { c ->
                    if (c.moveToFirst()) {
                        val old = Camera(oldId, GeoPoint(c.getDouble(0), c.getDouble(1)),
                            CameraType.valueOf(c.getString(2)), CameraSource.LUFOP)
                        val target = CameraLayers.reconcileId(old, cameras)
                        if (target != null && target !in ownerIds) {
                            database.execSQL("UPDATE OR IGNORE camera_corrections SET id=? WHERE id=?", arrayOf(target, oldId))
                            database.execSQL("UPDATE OR IGNORE suppressed_cameras SET id=? WHERE id=?", arrayOf(target, oldId))
                            database.execSQL("UPDATE OR IGNORE camera_aliases SET b=? WHERE b=?",arrayOf(target,oldId))
                            ownerIds += target
                        }
                    }
                }
            }
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
            database.delete("import_status", null, null)
            database.insertOrThrow("import_status", null, ContentValues().apply {
                put("attempted", System.currentTimeMillis()); putNull("failure")
            })
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        OwnerDataRevision.cameras++
        return cameras.size
    }
    override fun userCameras(): List<Camera> = readableDatabase.rawQuery("SELECT id,lat,lon,type,direction,mph,note,updated,bidirectional FROM cameras", null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(Camera(cursor.getString(0), GeoPoint(cursor.getDouble(1), cursor.getDouble(2)),
                CameraType.valueOf(cursor.getString(3)), CameraSource.USER,
                if (cursor.isNull(4)) null else cursor.getDouble(4), if (cursor.isNull(5)) null else cursor.getInt(5),
                cursor.getString(6), cursor.getLong(7), cursor.getInt(8) != 0))
        }
    }.let { JunctionStore(this).attach(it) }
    override fun upsert(camera: Camera) {
        require(camera.type != CameraType.MOBILE && camera.mobileReport == null && camera.source == CameraSource.USER && camera.id.isNotBlank() && camera.id.length<=100 &&
            camera.point.lat.isFinite() && camera.point.lat in -90.0..90.0 && camera.point.lon.isFinite() && camera.point.lon in -180.0..180.0 &&
            (camera.direction==null || camera.direction.isFinite() && camera.direction>=0 && camera.direction<360) &&
            (camera.enforcedMph==null || camera.enforcedMph in 5..130) && (camera.note?.length ?: 0)<=100 &&
            (!camera.bidirectional || camera.direction != null))
        val values = ContentValues().apply {
            put("id", camera.id); put("lat", camera.point.lat); put("lon", camera.point.lon)
            put("type", camera.type.name); put("direction", camera.direction); put("mph", camera.enforcedMph)
            put("note", camera.note); put("updated", System.currentTimeMillis())
            put("bidirectional", if (camera.bidirectional) 1 else 0)
        }
        val database = writableDatabase
        database.beginTransaction()
        try {
            check(database.insertWithOnConflict("cameras", null, values, SQLiteDatabase.CONFLICT_REPLACE) >= 0) { "Could not save camera" }
            JunctionStore(this).setMembership(camera)
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        OwnerDataRevision.cameras++
    }
    override fun delete(id: String) {
        val database = writableDatabase; database.beginTransaction()
        try {
            database.delete("junction_members", "camera_id=?", arrayOf(id))
            database.delete("cameras", "id=?", arrayOf(id)); database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        OwnerDataRevision.cameras++
    }
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
                    put("bidirectional", if (camera.bidirectional) 1 else 0)
                }
                if (database.insertWithOnConflict("cameras", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0)
                    error("Could not restore camera")
                JunctionStore(this).setMembership(camera)
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        OwnerDataRevision.cameras++
    }
    fun create(point: GeoPoint, type: CameraType, direction: Double? = null, mph: Int? = null,
        note: String? = null, bidirectional: Boolean = false, junction: CameraJunction? = null): Camera {
        val camera = Camera(UUID.randomUUID().toString(), point, type, CameraSource.USER,
            direction, mph, note, bidirectional = bidirectional, junction = junction)
        upsert(camera); return camera
    }
}

data class OsmSnapshot(val center: GeoPoint, val fetchedAt: Long, val roads: List<Road>, val cameras: List<Camera>, val averageSections: List<AverageSpeedSection> = emptyList()) {
    // Query covers a 1.5 km latitude/longitude box around the centre. Leave a safety margin.
    fun usable(point: GeoPoint, now: Long): Boolean = now - fetchedAt in 0..2_592_000_000L &&
        kotlin.math.abs(point.lat - center.lat) < 1150.0 / 111195.0 &&
        kotlin.math.abs(point.lon - center.lon) < 1150.0 / (111320.0 * cos(Math.toRadians(center.lat)))
}

object OsmCoverage {
    fun refreshTarget(snapshot: OsmSnapshot?, fix: Fix, nowMs: Long, lastAttemptMs: Long): GeoPoint? {
        if (lastAttemptMs > 0 && nowMs - lastAttemptMs < 90_000) return null
        if (snapshot == null || !snapshot.usable(fix.point, nowMs) || nowMs - snapshot.fetchedAt > 86_400_000) return fix.point
        val distance = Geo.distance(snapshot.center, fix.point)
        val movingAway = fix.bearing?.let { Geo.difference(it, Geo.bearing(snapshot.center, fix.point)) < 70 } ?: false
        if (distance < 250 || !movingAway) return null
        return fix.bearing?.let { Geo.ahead(fix.point, it, 900.0) } ?: fix.point
    }
}

class OsmDataSource(private val context: Context, private val cacheName: String = "drive") {
    private val cacheFile get() = File(context.filesDir, if (cacheName == "drive") "osm-snapshot.json" else "osm-$cacheName-snapshot.json")
    private val cachePrefs get() = if (cacheName == "drive") "cache" else "cache-$cacheName"
    fun cached(point: GeoPoint? = null): OsmSnapshot? = runCatching {
        RoadCache(context).use { cache ->
            val region = cache.latest(point)
            if (region != null) decode(JSONObject(region.json),region.fetchedAt,region.center)
            else if (cacheFile.exists()) {
                val legacy = decode(JSONObject(cacheFile.inputStream().use { BoundedIo.text(it,8_000_000) }), cacheFile.lastModified())
                cache.save(CachedRoadRegion(legacy.center,legacy.fetchedAt,cacheFile.readText()))
                if(point == null || legacy.usable(point,System.currentTimeMillis())) legacy else null
            } else null
        }
    }.getOrNull()
    fun fetch(point: GeoPoint): OsmSnapshot {
        val latStep = 1.5 / 111.195; val lonStep = 1.5 / (111.32 * cos(Math.toRadians(point.lat)))
        val box = "${point.lat - latStep},${point.lon - lonStep},${point.lat + latStep},${point.lon + lonStep}"
        val query = """[out:json][timeout:25];(way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"]($box);node["highway"="speed_camera"]($box);node["enforcement"~"maxspeed|traffic_signals|red_light_camera|average_speed"]($box);relation["type"="enforcement"]["enforcement"~"maxspeed|traffic_signals|red_light_camera|average_speed"]($box););out geom;"""
        val connection = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 8000; connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "SpeedBuddy/0.1 (owner-first driving utility)")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.outputStream.use { it.write("data=${java.net.URLEncoder.encode(query, "UTF-8")}".toByteArray()) }
            if (connection.responseCode != 200) throw IllegalStateException("Overpass HTTP ${connection.responseCode}")
            val response = connection.inputStream.use { BoundedIo.text(it,8_000_000) }
            if (response.length > 8_000_000) throw IllegalStateException("Map response too large")
            val snapshot = decode(JSONObject(response), System.currentTimeMillis(), point)
            RoadCache(context).use { it.save(CachedRoadRegion(point,snapshot.fetchedAt,response)) }
            OwnerDataRevision.roads++
            return snapshot
        } finally { connection.disconnect() }
    }
    internal fun decode(json: JSONObject, fetched: Long, centerOverride: GeoPoint? = null): OsmSnapshot {
        val center = centerOverride ?: run {
            val saved = context.getSharedPreferences(cachePrefs, Context.MODE_PRIVATE)
            GeoPoint(saved.getString("lat", "0")!!.toDouble(), saved.getString("lon", "0")!!.toDouble())
        }
        val roads = mutableListOf<Road>(); val cameras = linkedMapOf<String, Camera>()
        val sections=mutableListOf<AverageSpeedSection>()
        val elements = json.getJSONArray("elements")
        require(elements.length()<=50_000) { "Road response has too many records" }
        val ordered=(0 until elements.length()).mapNotNull { elements.optJSONObject(it) }.sortedBy { if(it.optString("type")=="relation") 1 else 0 }
        val ambiguousDirections=hashSetOf<String>()
        fun point(value: JSONObject): GeoPoint {
            val result=GeoPoint(value.getDouble("lat"),value.getDouble("lon"))
            require(result.lat.isFinite() && result.lat in -90.0..90.0 && result.lon.isFinite() && result.lon in -180.0..180.0)
            return result
        }
        for (element in ordered) { try {
            val kind = element.getString("type")
            val tags = element.optJSONObject("tags") ?: JSONObject()
            if (kind == "way") {
                val geometry = element.optJSONArray("geometry") ?: continue
                require(geometry.length()<=10_000)
                val points = (0 until geometry.length()).map { point(geometry.getJSONObject(it)) }
                if (points.size > 1) roads += Road("way/${element.getLong("id")}", tags.optString("name").takeIf { it.isNotBlank() }, points, tagMap(tags))
            } else if (kind == "node") {
                val type = CameraCategories.fromOsm(tags.optString("enforcement"), tags.optString("highway")) ?: continue
                val id = "node/${element.getLong("id")}"; cameras[id] = Camera(id, point(element), type,
                    CameraSource.OSM, null, SpeedLimits.mph(tagMap(tags)), updatedAtMs = fetched)
            } else if (kind == "relation") {
                AverageSpeedSections.parse(element)?.let(sections::add)
                val type = CameraCategories.fromOsm(tags.optString("enforcement"), "") ?: continue
                val members = element.optJSONArray("members") ?: continue
                val relationBearing = runCatching { OsmEnforcementDirection.travel(members) }.getOrNull()
                for (j in 0 until members.length()) {
                    val member = members.getJSONObject(j)
                    if (member.optString("role") != "device" || member.optString("type") != "node") continue
                    val id = "node/${member.getLong("ref")}"
                    val existing=cameras[id]
                    if (!member.has("lat") || !member.has("lon")) continue
                    val opposite=existing?.direction!=null && relationBearing!=null && Geo.difference(existing.direction,(relationBearing+180)%360)<15
                    if(existing?.direction!=null && relationBearing!=null && !opposite && Geo.difference(existing.direction,relationBearing)>15) ambiguousDirections+=id
                    cameras[id] = Camera(id, point(member), type,
                        CameraSource.OSM, if(id in ambiguousDirections) null else existing?.direction ?: relationBearing,
                        SpeedLimits.mph(tagMap(tags)) ?: existing?.enforcedMph, updatedAtMs = fetched,
                        bidirectional=id !in ambiguousDirections && (opposite || existing?.bidirectional==true))
                }
            }
        } catch(_: Exception) { /* Malformed individual records do not discard valid neighbours. */ } }
        return OsmSnapshot(center, fetched, roads, cameras.values.toList(),sections)
    }
    private fun tagMap(tags: JSONObject): Map<String, String> = tags.keys().asSequence().associateWith { tags.getString(it) }
}
