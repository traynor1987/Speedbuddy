package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class ReleaseCatalogTest {
    private fun release(tag: String, assets: String) = """{"tag_name":"$tag","html_url":"https://github.com/traynor1987/Speedbuddy/releases/tag/$tag","draft":false,"prerelease":false,"assets":[$assets]}"""
    private val asset = """{"name":"SpeedBuddy-release.apk","browser_download_url":"https://github.com/traynor1987/Speedbuddy/releases/download/v0.1.5/SpeedBuddy-release.apk","size":70000000,"digest":"sha256:${"a".repeat(64)}"}"""

    @Test fun acceptsNewerPublishedReleaseWithDigest() {
        val result = ReleaseCatalog.parse(release("v0.1.5", asset), "0.1.4")
        assertEquals("0.1.5", result?.version)
        assertEquals("a".repeat(64), result?.sha256)
    }

    @Test fun rejectsUnchangedAndOlderVersions() {
        assertNull(ReleaseCatalog.parse(release("v0.1.4", asset), "0.1.4"))
        assertNull(ReleaseCatalog.parse(release("v0.1.3", asset), "0.1.4"))
        assertTrue(ReleaseCatalog.isNewer("0.2.0", "0.1.9"))
    }

    @Test fun rejectsUnsafeOrIncompleteRelease() {
        assertThrows(IllegalArgumentException::class.java) { ReleaseCatalog.parse(release("v0.1.5", asset.replace("github.com", "example.org")), "0.1.4") }
        assertThrows(IllegalArgumentException::class.java) { ReleaseCatalog.parse(release("v0.1.5", asset.replace("sha256:", "sha1:")), "0.1.4") }
        assertThrows(IllegalArgumentException::class.java) { ReleaseCatalog.parse(release("v0.1.5", ""), "0.1.4") }
        assertThrows(IllegalArgumentException::class.java) { ReleaseCatalog.parse(release("v0.1.5", asset.replace("70000000", "999999999")), "0.1.4") }
        assertThrows(IllegalArgumentException::class.java) { ReleaseCatalog.parse(release("v0.1.5", asset).replace("\"draft\":false", "\"draft\":true"), "0.1.4") }
    }
}
