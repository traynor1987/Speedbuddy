package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class RegionalPackFilesTest {
    private fun isolated(test: (RegionalPackFiles,File)->Unit) {
        val root=Files.createTempDirectory("regional-files-test").toFile()
        try { test(RegionalPackFiles.at(root),root) } finally { root.deleteRecursively() }
    }
    @Test fun readerLeasePreventsDeletionUntilTheReaderCloses()=isolated { files,root ->
        val db=File(root,"lancashire/v1/roads.sqlite").apply { parentFile.mkdirs();writeBytes(ByteArray(4096)) }
        val executor=Executors.newFixedThreadPool(2);val opened=CountDownLatch(1);val close=CountDownLatch(1);val deleting=CountDownLatch(1)
        try {
            val reader=executor.submit { files.read { db.inputStream().use { opened.countDown();assertTrue(close.await(3,TimeUnit.SECONDS));assertEquals(0,it.read()) } } }
            assertTrue(opened.await(3,TimeUnit.SECONDS))
            val deletion=executor.submit { deleting.countDown();files.write { files.remove(files.region("lancashire")) } }
            assertTrue(deleting.await(3,TimeUnit.SECONDS));assertFalse(deletion.isDone);assertTrue(db.exists())
            close.countDown();reader.get(3,TimeUnit.SECONDS);deletion.get(3,TimeUnit.SECONDS);assertFalse(db.exists());assertEquals(0L,files.bytes())
        } finally { close.countDown();executor.shutdownNow() }
    }
    @Test fun deletionCancelsAnAlreadyRunningDownloadAndDuplicateDownloadIsRejected()=isolated { files,_ ->
        files.download("lancashire") { revision ->
            assertThrows(IllegalStateException::class.java) { files.download("lancashire") {} }
            files.write { files.invalidate("lancashire") }
            assertThrows(IllegalStateException::class.java) { files.write { files.checkCurrent("lancashire",revision) } }
        }
        files.download("lancashire") { files.write { files.checkCurrent("lancashire",it) } }
    }
    @Test fun recoveryReclaimsObsoleteVersionsAndOrphansButKeepsActiveWorkAndOtherRegions()=isolated { files,root ->
        for(path in listOf("lancashire/old/roads.sqlite","lancashire/current/roads.sqlite","merseyside/current/roads.sqlite",".staging-dead/roads.sqlite",".download-dead","active/lancashire.tmp"))
            File(root,path).apply { parentFile.mkdirs();writeText("data") }
        val working=files.temporary("download").apply { writeText("in flight") }
        files.recover(mapOf("lancashire" to "current","merseyside" to "current"))
        assertTrue(working.exists());assertTrue(File(root,"lancashire/current/roads.sqlite").exists());assertTrue(File(root,"merseyside/current/roads.sqlite").exists())
        assertFalse(File(root,"lancashire/old").exists());assertFalse(File(root,".download-dead").exists());assertFalse(File(root,".staging-dead").exists());assertFalse(File(root,"active/lancashire.tmp").exists())
        files.release(working);assertFalse(working.exists());assertEquals(8L,files.bytes())
    }
    @Test fun invalidRegionCannotReachUnrelatedFiles()=isolated { files,_ ->
        assertThrows(IllegalArgumentException::class.java) { files.region("../roads.db") }
        assertThrows(IllegalArgumentException::class.java) { files.region("active/") }
    }
}
