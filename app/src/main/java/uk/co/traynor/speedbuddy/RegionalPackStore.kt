package uk.co.traynor.speedbuddy

import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import org.json.JSONObject
import java.util.zip.GZIPInputStream

/** App-private, verified regional road packs. Owner data remains in RoadDb. */
internal class RegionalPackStore(private val context: Context) {
    private val root = File(context.filesDir, "regional-road-packs").apply { mkdirs() }
    private val active = File(root, "active")
    private val files=RegionalPackFiles.at(root)
    private var recoveryError: String?=null
    init { files.write {
        runCatching { recover() }.onFailure { recoveryError="Regional storage cleanup incomplete; stored bytes remain counted" }
        // Previous releases placed temporary downloads in cache; only that exact namespace is reclaimed.
        context.cacheDir.listFiles().orEmpty().filter { it.name.matches(Regex("regional-pack-[a-f0-9-]{36}\\.gz")) }.forEach { if(!it.delete()) recoveryError="Abandoned regional download could not be reclaimed" }
    } }
    fun <T> reading(action: ()->T): T=files.read(action)
    fun <T> downloading(region: String,action: (Long)->T): T=files.download(region,action)
    fun temporaryDownload(): File=files.temporary("download")
    fun releaseTemporary(file: File)=files.release(file)
    fun storedBytes(): Long=files.bytes()
    private fun recover() {
        val versions=active.listFiles { f -> f.extension=="json" }.orEmpty().mapNotNull { pointer ->
            descriptor(pointer)?.takeIf { it.id==pointer.nameWithoutExtension }?.let { it.id to it.version }
        }.toMap()
        // Preserve folders with a damaged pointer for explicit repair/delete, rather than guessing.
        val damaged=active.listFiles { f -> f.extension=="json" }.orEmpty().filter { descriptor(it)?.id!=it.nameWithoutExtension }.map { it.nameWithoutExtension }.toSet()
        files.recover(versions,damaged)
    }
    private fun descriptor(pointer: File): RegionalPackDescriptor?=runCatching {
        RegionalRoadCatalogue.parse(JSONObject().put("catalogueVersion",1).put("generatedAt","local")
            .put("regions",org.json.JSONArray().put(JSONObject(pointer.readText()))).toString()).single()
    }.getOrNull()
    data class Installed(val descriptor: RegionalPackDescriptor, val database: File,val problem: String?=null)
    enum class InstallStage { VERIFYING_DOWNLOAD, DECOMPRESSING, VERIFYING_DATABASE, ACTIVATING }
    data class Snapshot(val installed: List<Installed>,val generation: String,val unavailable: Boolean)
    /** Read each atomic pointer once. Bad pointers never prevent other regions from loading. */
    fun snapshot(): Snapshot=files.read {
        val installed=mutableListOf<Installed>();val identities=mutableListOf<String>();var unavailable=recoveryError!=null || !root.isDirectory
        for(pointer in active.listFiles { file -> file.extension=="json" }.orEmpty().sortedBy { it.name }) {
            val text=runCatching { pointer.readText() }.getOrNull()
            val descriptor=text?.let { runCatching { RegionalRoadCatalogue.parse("{\"catalogueVersion\":1,\"generatedAt\":\"local\",\"regions\":[$it]}").single() }.getOrNull() }
            val database=descriptor?.takeIf { it.id==pointer.nameWithoutExtension }?.let { File(root,"${it.id}/${it.version}/roads.sqlite") }
            identities+="${pointer.name}:${text?.hashCode()}:${database?.length()}:${database?.lastModified()}"
            if(descriptor!=null && database?.isFile==true) installed+=Installed(descriptor,database) else unavailable=true
        }
        Snapshot(installed,identities.joinToString("|"),unavailable)
    }
    data class Inventory(val installed: List<Installed>,val issues: List<String>,val error: String?=null)
    fun inventory(): Inventory=files.read {
        val listed=mutableListOf<Installed>();val issues=mutableListOf<String>()
        active.listFiles { f -> f.extension=="json" }.orEmpty().sortedBy { it.name }.forEach { pointer ->
            val d=descriptor(pointer)?.takeIf { it.id==pointer.nameWithoutExtension }
            if(d==null) issues+=pointer.nameWithoutExtension
            else {
                val database=File(root,"${d.id}/${d.version}/roads.sqlite")
                val problem=if(!database.isFile) "Database missing; re-download or delete" else runCatching {
                    SQLiteDatabase.openDatabase(database.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery("PRAGMA quick_check(1)",null).use { c -> check(c.moveToFirst() && c.getString(0)=="ok") }
                    }
                    null
                }.getOrElse { "Database unreadable; re-download or delete" }
                listed+=Installed(d,database,problem)
            }
        }
        Inventory(listed,issues,recoveryError ?: if(!root.isDirectory) "Regional storage unavailable" else null)
    }
    fun installed(): List<Installed> = snapshot().installed
    fun activeDatabases(): List<Pair<String,File>> = installed().map { it.descriptor.id to it.database }
    fun active(region: String): File? = installed().firstOrNull { it.descriptor.id==region }?.database
    /** Validates a complete downloaded gzip then atomically changes only this region's pointer. */
    fun install(descriptor: RegionalPackDescriptor,gzip: File,progress: (InstallStage)->Unit = {}): Installed =
        downloading(descriptor.id) { revision -> installDownloaded(descriptor,gzip,revision,progress) }
    fun installDownloaded(descriptor: RegionalPackDescriptor,gzip: File,revision: Long,progress: (InstallStage)->Unit = {}): Installed {
        files.region(descriptor.id)
        require(descriptor.version.matches(Regex("[a-zA-Z0-9._-]{1,100}")) && descriptor.version !in setOf(".",".."))
        progress(InstallStage.VERIFYING_DOWNLOAD)
        require(gzip.length()==descriptor.downloadBytes) { "Pack size mismatch" }
        require(sha256(gzip)==descriptor.sha256) { "Pack checksum mismatch" }
        val staging=files.temporary("staging",true)
        val database=File(staging,"roads.sqlite")
        try {
            progress(InstallStage.DECOMPRESSING)
            GZIPInputStream(FileInputStream(gzip)).use { input -> FileOutputStream(database).use { output ->
                val buffer=ByteArray(64*1024);var total=0L
                while(true) { val count=input.read(buffer);if(count<0)break;total+=count
                    require(total<=descriptor.uncompressedBytes && total<=512L*1024*1024) { "Pack decompression limit" }
                    output.write(buffer,0,count) }
                require(total==descriptor.uncompressedBytes) { "Pack raw size mismatch" }
                output.fd.sync()
            } }
            require(sha256(database)==descriptor.uncompressedSha256) { "Pack raw checksum mismatch" }
            progress(InstallStage.VERIFYING_DATABASE);validateDatabase(database,descriptor)
            progress(InstallStage.ACTIVATING)
            return files.write {
                files.checkCurrent(descriptor.id,revision)
                val target=File(files.region(descriptor.id),descriptor.version).apply { parentFile?.mkdirs() }
                val previous=descriptor(File(active,"${descriptor.id}.json"))
                val existing=File(target,"roads.sqlite")
                if(target.exists() && (!existing.isFile || sha256(existing)!=descriptor.uncompressedSha256)) {
                    val old=descriptor(File(active,"${descriptor.id}.json"))
                    require(old==null || old.version!=descriptor.version || !existing.isFile || sha256(existing)!=old.uncompressedSha256) { "Conflicting immutable pack" }
                    // A verified replacement may repair corrupted files after all readers close.
                    files.remove(target)
                }
                if(!target.exists()) check(staging.renameTo(target)) { "Could not activate pack files" }
                active.mkdirs()
                val pointer=files.temporary("pointer")
                try {
                    FileOutputStream(pointer).use { out -> out.write(descriptorJson(descriptor).toByteArray(Charsets.UTF_8));out.fd.sync() }
                    check(pointer.renameTo(File(active,"${descriptor.id}.json"))) { "Could not switch active pack" }
                } catch(error: Exception) {
                    if(previous?.version!=descriptor.version) files.remove(target)
                    throw error
                } finally { files.release(pointer) }
                // Pointer now owns the verified version. Failed updates never reach this cleanup.
                target.parentFile!!.listFiles().orEmpty().filter { it!=target }.forEach(files::remove)
                Installed(descriptor,File(target,"roads.sqlite"))
            }
        } finally { files.release(staging) }
    }
    data class Deletion(val freedBytes: Long,val retainedBytes: Long)
    fun delete(region: String): Deletion=files.write {
        val folder=files.region(region);files.invalidate(region)
        val pointer=File(active,"$region.json")
        val oldTemporaryPointer=File(active,"$region.tmp")
        val before=files.bytes(region)+pointer.length()+oldTemporaryPointer.length()
        check(!pointer.exists() || pointer.delete()) { "Could not deactivate regional pack" }
        check(!oldTemporaryPointer.exists() || oldTemporaryPointer.delete()) { "Could not remove regional pointer work" }
        files.remove(folder)
        val remaining=files.bytes(region)+pointer.length()+oldTemporaryPointer.length()
        Deletion((before-remaining).coerceAtLeast(0),files.bytes())
    }
    private fun validateDatabase(file: File,d: RegionalPackDescriptor) {
        SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use { db ->
            require(db.rawQuery("PRAGMA integrity_check",null).use { it.moveToFirst() && it.getString(0)=="ok" }) { "Pack integrity check failed" }
            val metadata=db.rawQuery("SELECT key,value FROM metadata",null).use { c -> buildMap { while(c.moveToNext()) put(c.getString(0),c.getString(1)) } }
            RegionalPackManifest.verifyMetadata(metadata,d.id)
            db.rawQuery("SELECT osm_way_id,coordinates,tags FROM roads LIMIT 1",null).close()
            db.rawQuery("SELECT osm_way_id,min_lon,max_lon,min_lat,max_lat FROM roads_rtree LIMIT 1",null).close()
        }
    }
    private fun descriptorJson(d: RegionalPackDescriptor)=JSONObject().put("id",d.id).put("displayName",d.displayName)
        .put("version",d.version).put("schemaVersion",d.schemaVersion).put("osmTimestamp",d.osmTimestamp)
        .put("downloadBytes",d.downloadBytes).put("uncompressedBytes",d.uncompressedBytes).put("sha256",d.sha256)
        .put("uncompressedSha256",d.uncompressedSha256).put("downloadUrl",d.downloadUrl).put("manifestUrl",d.manifestUrl).toString()
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest -> FileInputStream(file).use { input ->
        val buffer=ByteArray(64*1024); while(true) { val count=input.read(buffer); if(count<0) break; digest.update(buffer,0,count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    } }
}
