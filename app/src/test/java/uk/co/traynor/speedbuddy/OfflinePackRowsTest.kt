package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class OfflinePackRowsTest {
    private fun pack(id: String,version: String)=RegionalPackDescriptor(id,id,version,1,"2026-10-08T00:00:00Z",1,1,"a".repeat(64),"b".repeat(64),"https://example.test/pack","https://example.test/manifest")
    @Test fun absentCatalogueDoesNotHideLocalPacks() {
        val installed=listOf(pack("lancashire","old"),pack("merseyside","current"))
        assertEquals(installed,OfflinePackRows.merge(emptyList(),installed).map { it.installed })
    }
    @Test fun catalogueUpdateDoesNotPretendNewVersionIsInstalled() {
        val old=pack("lancashire","old");val newer=pack("lancashire","new")
        val rows=OfflinePackRows.merge(listOf(newer,pack("merseyside","1")),listOf(old))
        assertEquals(2,rows.size);assertEquals(newer,rows.first().pack);assertEquals(old,rows.first().installed);assertNull(rows.last().installed)
    }
}
