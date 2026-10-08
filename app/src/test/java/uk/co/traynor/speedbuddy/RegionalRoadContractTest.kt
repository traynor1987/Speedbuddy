package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

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
        val combined=JSONObject(catalogue)
        val lancashire=JSONObject(combined.getJSONArray("regions").getJSONObject(0).toString())
        lancashire.put("id","lancashire").put("displayName","Lancashire").put("version","20261005-1")
        lancashire.put("downloadUrl","https://api.jtwebsolutions.co.uk/speedbuddy/v1/packs/lancashire/20261005-1/roads.sqlite.gz")
        lancashire.put("manifestUrl","https://api.jtwebsolutions.co.uk/speedbuddy/v1/packs/lancashire/20261005-1/manifest.json")
        combined.getJSONArray("regions").put(lancashire)
        assertEquals(setOf("merseyside","lancashire"),RegionalRoadCatalogue.parse(combined.toString()).map { it.id }.toSet())
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

    @Test fun releasedManifestCrossChecksCatalogueRatherThanTrustingDownloadAlone() {
        val d=RegionalRoadCatalogue.parse(catalogue).single()
        val manifest=JSONObject().put("format","speedbuddy-roadpack-sqlite-v1").put("formatVersion",1)
            .put("schemaVersion",1).put("matcherVersion",RegionalPackManifest.MATCHER).put("coordinateSystem","EPSG:4326")
            .put("region",d.id).put("packVersion",d.version).put("download",JSONObject().put("bytes",d.downloadBytes)
                .put("uncompressedBytes",d.uncompressedBytes).put("sha256",d.sha256).put("uncompressedSha256",d.uncompressedSha256))
        RegionalPackManifest.verify(manifest.toString(),d)
        for(key in listOf("region","packVersion","matcherVersion","coordinateSystem")) {
            val altered=JSONObject(manifest.toString()).put(key,"incompatible")
            assertThrows(IllegalArgumentException::class.java) { RegionalPackManifest.verify(altered.toString(),d) }
        }
        manifest.getJSONObject("download").put("sha256","0".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { RegionalPackManifest.verify(manifest.toString(),d) }
    }
    @Test fun productionLiveShapeRetainsInt64RoadIdentity() {
        val result=LiveRoadStateParser.parse("""{"matched":true,"road":{"osmWayId":4015482,"name":"North Linkside Road","highway":"residential"},"limit":{"mph":20.0,"raw":"20 mph","source":"osm:maxspeed"},"providerState":"ROAD_MATCHED_LIMIT_KNOWN","fallbackAllowed":false}""")
        assertEquals("way/4015482",result.roadId);assertEquals(20,result.limitMph)
        val large=LiveRoadStateParser.parse("""{"matched":true,"road":{"osmWayId":9007199254740993},"limit":{"mph":20},"providerState":"ROAD_MATCHED_LIMIT_KNOWN"}""")
        assertEquals("way/9007199254740993",large.roadId)
    }
}
