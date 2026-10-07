package uk.co.traynor.speedbuddy

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The decoded body cap is deliberate; callers can subdivide a geographic request safely. */
class RoadResponseTooLargeException(val decodedBytes: Int) : IllegalStateException("Road response too large")

data class AdaptiveRoadFetch(val data: RoadTileData,val requested: RoadTile,val parent: RoadTile) {
    val subdivisionLevel get() = requested.level
}

/**
 * Retries only an explicit oversized geographic region as the child containing the
 * driver.  Provider/network/parse failures are not retried here: the planner's normal
 * exponential backoff remains responsible for those.
 */
class AdaptiveRoadDownloader(private val db: RoadDb,private val load: suspend (RoadTile,Boolean) -> RoadTileData) {
    constructor(db: RoadDb,downloader: RoadDownload) : this(db,{ tile,immediate -> downloader.fetch(tile,immediate) })
    suspend fun fetch(parent: RoadTile, current: GeoPoint): AdaptiveRoadFetch {
        var target=RoadSubdivision.next(parent,current,db.subdivisions())
        var immediate=false
        while(true) try {
            return AdaptiveRoadFetch(load(target,immediate),target,parent)
        } catch(tooLarge: RoadResponseTooLargeException) {
            val child=RoadSubdivision.afterOversize(target,current) ?: throw IllegalStateException(
                "Road response too large at minimum dense-city region (level ${target.level})",tooLarge)
            // This durable marker prevents a later drive from repeatedly requesting the
            // known-oversized parent.  It contains no road data and is never coverage.
            db.markSubdivided(target)
            target=child
            immediate=true
        }
    }
}

/** Strict completion checks: HTTP 200 alone is not evidence of a complete Overpass response. */
object RoadResponse {
    private val keys=setOf("highway","name","ref","oneway","junction","maxspeed","maxspeed:type","source:maxspeed",
        "maxspeed:conditional","maxspeed:variable","maxspeed:lanes","maxspeed:forward","maxspeed:backward")
    fun decode(raw: String,tile: RoadTile,fetched: Long): RoadTileData {
        val j=JSONObject(raw)
        require(!j.has("remark")) { "Overpass response is incomplete" }
        val elements=j.getJSONArray("elements")
        require(elements.length() in 1..40_000)
        val count=elements.getJSONObject(elements.length()-1)
        require(count.getString("type")=="count" && count.getJSONObject("tags").getInt("total")==elements.length()-1) { "Missing or mismatched completion count" }
        val roads=mutableListOf<Road>();val cameras=linkedMapOf<String,Camera>();val seen=mutableSetOf<String>()
        fun category(tags: JSONObject): CameraType? = when {
            tags.optString("enforcement").let { "traffic_signals" in it || "red_light_camera" in it } -> CameraType.RED_LIGHT
            "maxspeed" in tags.optString("enforcement") || tags.optString("highway")=="speed_camera" -> CameraType.SPEED
            else -> null
        }
        fun map(tags: JSONObject)=tags.keys().asSequence().filter { it in keys }.associateWith { tags.getString(it) }
        for(i in 0 until elements.length()-1) {
            val e=elements.getJSONObject(i);val kind=e.getString("type");val id="$kind/${e.getLong("id")}";val tags=e.optJSONObject("tags")?:JSONObject()
            require(seen.add(id)) { "Duplicate source element" }
            when(kind) {
                "way" -> {
                    val geometry=e.getJSONArray("geometry");require(geometry.length() in 2..20_000)
                    val nodes=e.getJSONArray("nodes")
                    require(nodes.length()==geometry.length()) { "Incomplete way geometry" }
                    val points=(0 until geometry.length()).map { geometry.getJSONObject(it).let { p -> GeoPoint(p.getDouble("lat"),p.getDouble("lon")) } }
                    require(points.all(::validPoint))
                    roads.add(Road(id,tags.optString("name").takeIf { it.isNotBlank() },points,map(tags)))
                }
                "node" -> category(tags)?.let { type ->
                    val p=GeoPoint(e.getDouble("lat"),e.getDouble("lon"));require(validPoint(p))
                    cameras[id]=Camera(id,p,type,CameraSource.OSM,tags.optString("direction").toDoubleOrNull()?.takeIf { it in 0.0..<360.0 },SpeedLimits.mph(map(tags)),updatedAtMs=fetched)
                }
                "relation" -> category(tags)?.let { type ->
                    val members=e.getJSONArray("members")
                    for(k in 0 until members.length()) {
                        val m=members.getJSONObject(k)
                        if(m.optString("role")!="device" || m.optString("type")!="node") continue
                        val p=GeoPoint(m.getDouble("lat"),m.getDouble("lon"));require(validPoint(p))
                        val cameraId="node/${m.getLong("ref")}"
                        cameras.putIfAbsent(cameraId,Camera(cameraId,p,type,CameraSource.OSM,tags.optString("direction").toDoubleOrNull()?.takeIf { it in 0.0..<360.0 },SpeedLimits.mph(map(tags)),updatedAtMs=fetched))
                    }
                }
                else -> error("Unexpected source element")
            }
        }
        val richer=OsmRecordDecoder.decode(j,fetched,tile.center)
        return RoadTileData(tile,fetched,roads,richer.cameras,averageSections=richer.averageSections)
    }
    fun query(tile: RoadTile): String {
        val box="${tile.south},${tile.west},${tile.north},${tile.east}"
        return """[out:json][timeout:25][maxsize:67108864];(way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"]($box);node["highway"="speed_camera"]($box);node["enforcement"~"maxspeed|traffic_signals|red_light_camera|average_speed"]($box);relation["type"="enforcement"]["enforcement"~"maxspeed|traffic_signals|red_light_camera|average_speed"]($box););out body geom;out count;"""
    }
}
private object RoadRequestGate { val mutex=Mutex() }
class RoadDownload(context: Context,private val connectionFactory: () -> HttpURLConnection = {
    URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
}) {
    private val budget=context.getSharedPreferences("road-download-budget",Context.MODE_PRIVATE)
    private fun newDay() {
        val day=System.currentTimeMillis()/86_400_000
        if(budget.getLong("day",-1)!=day) check(budget.edit().putLong("day",day).putInt("requests",0).putLong("bytes",0).commit())
    }
    fun permitted(): Boolean { newDay();return budget.getInt("requests",0)<90 && budget.getLong("bytes",0)<9_000_000 }
    /** Called only by the single refresh coroutine. Count failed attempts and failed bytes too. */
    suspend fun fetch(tile: RoadTile, immediateRetry: Boolean = false): RoadTileData = RoadRequestGate.mutex.withLock {
        val wait=if(immediateRetry) 0 else 30_000-(System.currentTimeMillis()-budget.getLong("lastAttempt",0))
        if(wait>0) delay(wait)
        check(permitted()) { "Daily public-provider allowance reached" }
        check(budget.edit().putInt("requests",budget.getInt("requests",0)+1).putLong("lastAttempt",System.currentTimeMillis()).commit())
        suspendCancellableCoroutine { continuation ->
        val connection=connectionFactory()
        continuation.invokeOnCancellation { connection.disconnect() }
        var wireBytes=0L
        val remaining=9_000_000-budget.getLong("bytes",0)
        try {
            fun checkActive() { if(!continuation.isActive) throw CancellationException("Road update cancelled") }
            checkActive()
            connection.requestMethod="POST";connection.doOutput=true;connection.connectTimeout=8000;connection.readTimeout=30000
            connection.setRequestProperty("User-Agent","SpeedBuddy/0.2 (single-owner offline road cache; github.com/traynor1987/Speedbuddy)")
            connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty("Accept-Encoding","gzip")
            checkActive()
            connection.outputStream.use { it.write("data=${java.net.URLEncoder.encode(RoadResponse.query(tile),"UTF-8")}".toByteArray()) }
            checkActive()
            check(connection.responseCode==200) { "Overpass HTTP ${connection.responseCode}" }
            val counted=object: FilterInputStream(connection.inputStream) {
                override fun read(): Int { val n=super.read();if(n>=0) wireBytes++;check(wireBytes<=remaining);return n }
                override fun read(b: ByteArray,off: Int,len: Int): Int { val n=`in`.read(b,off,len);if(n>0) wireBytes+=n;check(wireBytes<=remaining);return n }
            }
            val stream: InputStream=if(connection.contentEncoding=="gzip") GZIPInputStream(counted) else counted
            val output=ByteArrayOutputStream()
            stream.use { input ->
                val buffer=ByteArray(8192)
                while(true) {
                    checkActive()
                    val n=input.read(buffer);if(n<0) break
                    if(output.size()+n>4_000_000) throw RoadResponseTooLargeException(output.size()+n)
                    output.write(buffer,0,n)
                }
            }
            val data=RoadResponse.decode(output.toString("UTF-8"),tile,System.currentTimeMillis())
            if(continuation.isActive) continuation.resume(data)
        } catch(e: Exception) {
            if(continuation.isActive) continuation.resumeWithException(e)
        } finally {
            budget.edit().putLong("bytes",budget.getLong("bytes",0)+wireBytes).commit();connection.disconnect()
        }
        }
    }
}
