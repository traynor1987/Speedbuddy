package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class RegionalRoadContractTest {
    private val catalogue = """{
      "catalogueVersion":1,"generatedAt":"2026-10-07T00:00:00Z","regions":[{
        "id":"merseyside","displayName":"Merseyside","version":"20261006-1","schemaVersion":1,
        "osmTimestamp":"2026-10-06T20:21:06Z","downloadBytes":16084855,"uncompressedBytes":77062144,
        "sha256":"fe9e2b2127134de77f504ce5c0900d96b09b1611f685439523a36a71b68bf5c9",
        "uncompressedSha256":"cd00465737c24b0e90fa0f7b57ae91ff385c119943f56d774ed1ca85157e4335",
        "downloadUrl":"https://api.jtwebsolutions.co.uk/speedbuddy/v1/packs/merseyside/20261006-1/roads.sqlite.gz",
        "manifestUrl":"https://api.jtwebsolutions.co.uk/speedbuddy/v1/packs/merseyside/20261006-1/manifest.json"
      }]}
    """.trimIndent()

    @Test fun `catalogue accepts immutable first party road pack`() {
        val pack = RegionalRoadCatalogue.parse(catalogue).single()
        assertEquals("merseyside", pack.id)
        assertEquals(16_084_855L, pack.downloadBytes)
        assertTrue(pack.downloadUrl.endsWith("/roads.sqlite.gz"))
    }

    @Test fun `catalogue recognises both released regions and rejects malformed entries`() {
        val lancashire=catalogue.replace("merseyside","lancashire").replace("Merseyside","Lancashire")
            .replace("20261006-1","20261005-1")
        val combined=catalogue.dropLast(2)+","+lancashire.substringAfter("[{").substringBeforeLast("]}")+"}] }"
        assertEquals(setOf("merseyside","lancashire"),RegionalRoadCatalogue.parse(combined).map { it.id }.toSet())
        assertThrows(IllegalArgumentException::class.java) { RegionalRoadCatalogue.parse(catalogue.replace("\"catalogueVersion\":1","\"catalogueVersion\":2")) }
        assertThrows(IllegalArgumentException::class.java) { RegionalRoadCatalogue.parse(catalogue.replace("\"downloadBytes\":16084855","\"downloadBytes\":0")) }
    }

    @Test fun `catalogue rejects cross host pack and unsupported schema`() {
        assertThrows(IllegalArgumentException::class.java) {
            RegionalRoadCatalogue.parse(catalogue.replace("api.jtwebsolutions.co.uk", "example.org"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegionalRoadCatalogue.parse(catalogue.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        }
    }

    @Test fun `unknown and uncertain never authorise overpass`() {
        assertFalse(RoadProviderState.ROAD_MATCHED_LIMIT_UNKNOWN.permitsOverpass)
        assertFalse(RoadProviderState.ROAD_MATCH_UNCERTAIN.permitsOverpass)
        assertTrue(RoadProviderState.COVERAGE_UNAVAILABLE.permitsOverpass)
        assertTrue(RoadProviderState.SERVICE_UNAVAILABLE.permitsOverpass)
    }

    @Test fun `live state preserves explicit known and unknown semantics`() {
        assertEquals(20, LiveRoadStateParser.parse("""{"matched":true,"limit":{"mph":20},"providerState":"ROAD_MATCHED_LIMIT_KNOWN","fallbackAllowed":false}""").limitMph)
        val unknown = LiveRoadStateParser.parse("""{"matched":true,"limit":null,"providerState":"ROAD_MATCHED_LIMIT_UNKNOWN","fallbackAllowed":false}""")
        assertNull(unknown.limitMph)
        assertEquals(RoadProviderState.ROAD_MATCHED_LIMIT_UNKNOWN, unknown.state)
    }
}
