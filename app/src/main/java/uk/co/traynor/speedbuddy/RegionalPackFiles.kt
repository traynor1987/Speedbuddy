package uk.co.traynor.speedbuddy

import java.io.File
import java.util.UUID
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Shared by every store/matcher in this process. Readers hold a lease until SQLite closes. */
internal class RegionalPackFiles private constructor(val root: File) {
    private val lock=ReentrantReadWriteLock(true)
    private val revisions=mutableMapOf<String,Long>()
    private val downloading=mutableSetOf<String>()
    private val temporary=mutableSetOf<File>()
    fun <T> read(action: ()->T): T=lock.read(action)
    fun <T> write(action: ()->T): T=lock.write(action)
    fun region(id: String): File {
        require(id.matches(Regex("[a-z0-9-]{1,64}"))) { "Invalid region id" }
        return File(root,id)
    }
    fun <T> download(id: String, action: (Long)->T): T {
        val revision=write { region(id);check(downloading.add(id)) { "A download for this region is already running" };revisions[id] ?: 0L }
        try { return action(revision) } finally { write { downloading.remove(id) } }
    }
    fun checkCurrent(id: String,revision: Long) {
        check((revisions[id] ?: 0L)==revision) { "Pack removed while download was running; activation cancelled" }
    }
    fun invalidate(id: String) { revisions[id]=(revisions[id] ?: 0L)+1 }
    fun temporary(prefix: String,directory: Boolean=false): File=write {
        File(root,".$prefix-${UUID.randomUUID()}").also { if(directory) check(it.mkdirs());temporary+=it }
    }
    fun release(file: File) = write { try { remove(file) } finally { temporary.remove(file) };Unit }
    fun bytes(id: String?=null): Long=read {
        val folder=if(id==null) root else region(id)
        if(!folder.exists()) 0L else Files.walk(folder.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it,LinkOption.NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum()
        }
    }
    /** Never follows links or removes owner databases; failures leave truthful retained byte counts. */
    fun remove(file: File) {
        check(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "Pack path outside storage" }
        if(file.exists()) Files.walk(file.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
    fun recover(activeVersions: Map<String,String>,protectedRegions: Set<String> = emptySet())=write {
        root.listFiles().orEmpty().forEach { child ->
            when {
                child.name.startsWith('.') && child !in temporary -> remove(child)
                child.name=="active" -> child.listFiles().orEmpty().filter { it.extension=="tmp" }.forEach(::remove)
                child.name !in protectedRegions && child.isDirectory && child.name.matches(Regex("[a-z0-9-]{1,64}")) -> {
                    child.listFiles().orEmpty().filter { it.name!=activeVersions[child.name] }.forEach(::remove)
                    if(child.listFiles().isNullOrEmpty()) remove(child)
                }
            }
        }
    }
    companion object {
        private val stores=mutableMapOf<String,RegionalPackFiles>()
        @Synchronized fun at(root: File): RegionalPackFiles=stores.getOrPut(root.canonicalPath) { root.mkdirs();RegionalPackFiles(root) }
    }
}
