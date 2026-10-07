package uk.co.traynor.speedbuddy

import java.net.HttpURLConnection
import java.net.URL

/** Authenticated regional fallback. It never sends a synthetic heading or a token in a URL. */
internal class SpeedBuddyRoadClient(private val credential: () -> String?, private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    fun request(fix: Fix, previousWayId: Long? = null): LiveRoadState {
        require(fix.bearing != null && fix.bearing in 0.0..<360.0) { "Reliable heading required" }
        require(fix.accuracyM in 1.0..100.0) { "Invalid location accuracy" }
        val token=credential()?.takeIf(String::isNotBlank) ?: return LiveRoadState(RoadProviderState.SERVICE_UNAVAILABLE,false,null,false)
        val query=listOf("lat=${fix.point.lat}","lon=${fix.point.lon}","heading=${fix.bearing}","accuracyMetres=${fix.accuracyM}")+
            listOfNotNull(previousWayId?.let { "previousWayId=$it" },fix.speedMps?.let { "speedMps=$it" })
        val c=open(URL("https://api.jtwebsolutions.co.uk/speedbuddy/v1/road-state?${query.joinToString("&")}"))
        try { c.requestMethod="GET";c.connectTimeout=3000;c.readTimeout=5000;c.setRequestProperty("Authorization","Bearer $token")
            val code=c.responseCode
            if(code==503) return LiveRoadState(RoadProviderState.SERVICE_UNAVAILABLE,false,null,true)
            if(code !in 200..299) throw IllegalStateException("Road API HTTP $code")
            return c.inputStream.bufferedReader().use { LiveRoadStateParser.parse(it.readText()) }
        } finally { c.disconnect() }
    }
}
