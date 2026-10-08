package uk.co.traynor.speedbuddy

import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream

/** App-private, verified regional road packs. Owner data remains in RoadDb. */
internal class RegionalPackStore(private val context: Context) {
    private val root = File(context.filesDir, "regional-road-packs").apply { mkdirs() }
    private val active = File(root, "active")
    data class Installed(val descriptor: RegionalPackDescriptor, val database: File)
    enum class InstallStage { VERIFYING_DOWNLOAD, DECOMPRESSING, VERIFYING_DATABASE, ACTIVATING }
    fun installed(): List<Installed> = active.listFiles { file -> file.extension == "json" }?.mapNotNull { pointer ->
        val region=pointer.nameWithoutExtension
        val descriptor=runCatching { RegionalRoadCatalogue.parse("{\"catalogueVersion\":1,\"generatedAt\":\"local\",\"regions\":[${pointer.readText()}]}").single() }.getOrNull()
        descriptor?.let { active(region)?.let { database -> Installed(it,database) } }
    }.orEmpty()
    fun activeDatabases(): List<Pair<String,File>> = active.listFiles { file -> file.extension == "json" }?.mapNotNull { pointer ->
        val region=pointer.nameWithoutExtension
        active(region)?.let { region to it }
    }.orEmpty()
    fun active(region: String): File? = File(active, "$region.json").takeIf(File::isFile)?.readText()
        ?.let { RegionalRoadCatalogue.parse("{\"catalogueVersion\":1,\"generatedAt\":\"local\",\"regions\":[$it]}").single() }
        ?.let { descriptor -> File(root, "$region/${descriptor.version}/roads.sqlite").takeIf(File::isFile) }
    /** Validates a complete downloaded gzip then atomically changes only this region's pointer. */
    fun install(descriptor: RegionalPackDescriptor, gzip: File, progress: (InstallStage) -> Unit = {}): Installed {
        progress(InstallStage.VERIFYING_DOWNLOAD)
        require(gzip.length() == descriptor.downloadBytes) { "Pack size mismatch" }
        require(sha256(gzip) == descriptor.sha256) { "Pack checksum mismatch" }
        val staging = File(root, ".staging-${UUID.randomUUID()}").apply { mkdirs() }
        val database = File(staging, "roads.sqlite")
        try {
            progress(InstallStage.DECOMPRESSING)
            GZIPInputStream(FileInputStream(gzip)).use { input -> FileOutputStream(database).use { output ->
                val buffer=ByteArray(64*1024); var total=0L
                while(true) { val count=input.read(buffer); if(count<0) break; total+=count
                    require(total<=descriptor.uncompressedBytes && total<=512L*1024*1024) { "Pack decompression limit" }
                    output.write(buffer,0,count) }
                require(total==descriptor.uncompressedBytes) { "Pack raw size mismatch" }
            } }
            require(sha256(database)==descriptor.uncompressedSha256) { "Pack raw checksum mismatch" }
            progress(InstallStage.VERIFYING_DATABASE)
            validateDatabase(database,descriptor)
            progress(InstallStage.ACTIVATING)
            val target=File(root,"${descriptor.id}/${descriptor.version}").apply { parentFile?.mkdirs() }
            require(!target.exists() || File(target,"roads.sqlite").let { it.isFile && sha256(it)==descriptor.uncompressedSha256 }) { "Conflicting immutable pack" }
            if(!target.exists()) require(staging.renameTo(target)) { "Could not activate pack files" }
            active.mkdirs()
            val pointer=File(active,"${descriptor.id}.tmp"); pointer.writeText(descriptorJson(descriptor))
            require(pointer.renameTo(File(active,"${descriptor.id}.json"))) { "Could not switch active pack" }
            return Installed(descriptor,File(target,"roads.sqlite"))
        } finally { if(staging.exists()) staging.deleteRecursively() }
    }
    fun delete(region: String) { File(active,"$region.json").delete() }
    private fun validateDatabase(file: File,d: RegionalPackDescriptor) {
        SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use { db ->
            require(db.rawQuery("PRAGMA integrity_check",null).use { it.moveToFirst() && it.getString(0)=="ok" }) { "Pack integrity check failed" }
            val metadata=db.rawQuery("SELECT key,value FROM metadata",null).use { c -> buildMap { while(c.moveToNext()) put(c.getString(0),c.getString(1)) } }
            require(metadata["formatVersion"]?.trim('"')=="1" && metadata["matcherVersion"]!=null && metadata["coverage"]!=null) { "Unsupported pack schema" }
            require(metadata["dataset"]?.contains("\"region\":\"${d.id}\"")==true) { "Pack region mismatch" }
            db.rawQuery("SELECT osm_way_id FROM roads_rtree LIMIT 1",null).close()
        }
    }
    private fun descriptorJson(d: RegionalPackDescriptor) = "{\"id\":\"${d.id}\",\"displayName\":\"${d.displayName}\",\"version\":\"${d.version}\",\"schemaVersion\":1,\"osmTimestamp\":\"${d.osmTimestamp}\",\"downloadBytes\":${d.downloadBytes},\"uncompressedBytes\":${d.uncompressedBytes},\"sha256\":\"${d.sha256}\",\"uncompressedSha256\":\"${d.uncompressedSha256}\",\"downloadUrl\":\"${d.downloadUrl}\",\"manifestUrl\":\"${d.manifestUrl}\"}"
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest -> FileInputStream(file).use { input ->
        val buffer=ByteArray(64*1024); while(true) { val count=input.read(buffer); if(count<0) break; digest.update(buffer,0,count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    } }
}
