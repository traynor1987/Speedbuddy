package uk.co.traynor.speedbuddy

import java.io.InputStream
import java.util.zip.ZipInputStream

/** The September 2026 free Europe ASC ZIP uses separate GBFixe*.asc and GBFeuRouge*.asc files.
 * ASC rows are longitude, latitude, quoted label. No trustworthy direction or mph unit is supplied.
 */
object LufopAscImporter {
    data class Batch(val cameras: List<Camera>, val archiveDateMs: Long?)
    private val row = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*\"([^\"]*)\"\s*$""")
    private val fixed = Regex("GBFixeGB(?:\\d+)?\\.asc", RegexOption.IGNORE_CASE)
    private val red = Regex("GBFeuRougeGB\\.asc", RegexOption.IGNORE_CASE)

    fun parse(input: InputStream): List<Camera> = inspect(input).cameras
    fun inspect(input: InputStream): Batch = inspectLimited(input,64_000_000)
    internal fun inspectLimited(input: InputStream,maximumExpandedBytes: Long): Batch {
        val cameras = linkedMapOf<String, Camera>()
        var bytes = 0L; var totalBytes=0L; var entries = 0; var invalid = 0
        var archiveDateMs: Long? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > 1000) throw IllegalArgumentException("Too many ZIP entries")
                val name = entry.name.substringAfterLast('/')
                val type = when {
                    fixed.matches(name) -> CameraType.SPEED
                    red.matches(name) -> CameraType.RED_LIGHT
                    else -> null
                }
                val data=java.io.ByteArrayOutputStream()
                if(!entry.isDirectory) {
                    val buffer=ByteArray(8192)
                    while(true) {
                        val count=zip.read(buffer);if(count<0) break
                        totalBytes+=count
                        require(totalBytes<=maximumExpandedBytes) { "Expanded camera archive is too large" }
                        if(type!=null) {
                            bytes+=count;require(bytes<=8_000_000) { "UK camera data too large" }
                            data.write(buffer,0,count)
                        }
                    }
                }
                if (type != null && !entry.isDirectory) {
                    if(entry.time>0) archiveDateMs=maxOf(archiveDateMs ?: 0L,entry.time)
                    data.toString(Charsets.UTF_8.name()).lineSequence().filter { it.isNotBlank() }.forEach { line ->
                        val match = row.matchEntire(line)
                        val lon = match?.groupValues?.get(1)?.toDoubleOrNull()
                        val lat = match?.groupValues?.get(2)?.toDoubleOrNull()
                        if (lat == null || lon == null || lat !in 49.0..61.0 || lon !in -9.0..3.0) invalid++
                        else {
                            val id = "lufop:${type.name}:${"%.6f".format(java.util.Locale.ROOT, lat)}:${"%.6f".format(java.util.Locale.ROOT, lon)}"
                            cameras[id] = Camera(id, GeoPoint(lat, lon), type, CameraSource.LUFOP)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        if (cameras.isEmpty() || invalid > cameras.size / 100) throw IllegalArgumentException("No usable UK fixed/red-light camera data (or invalid coordinates)")
        return Batch(cameras.values.toList(), archiveDateMs)
    }
}
