package uk.co.traynor.speedbuddy

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Production catalogue and download gateway. Verification and activation stay in [RegionalPackStore]. */
internal class RegionalPackLifecycle(private val context: Context, private val credential: () -> String?,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    private val store=RegionalPackStore(context)
    fun installed()=store.installed()
    fun catalogue(): List<RegionalPackDescriptor> = requestText("https://api.jtwebsolutions.co.uk/speedbuddy/v1/regions").let(RegionalRoadCatalogue::parse)
        .filter { it.id in supportedRegions }
    fun download(descriptor: RegionalPackDescriptor): RegionalPackStore.Installed {
        val staging=File(context.cacheDir,"regional-pack-${UUID.randomUUID()}.gz")
        try {
            val token=credential()?.takeIf(String::isNotBlank) ?: error("Sign in to download regional road data")
            val connection=open(URL(descriptor.downloadUrl))
            try {
                connection.requestMethod="GET"; connection.connectTimeout=10_000; connection.readTimeout=30_000
                connection.setRequestProperty("Authorization","Bearer $token")
                require(connection.responseCode in 200..299) { "Regional pack download failed (HTTP ${connection.responseCode})" }
                connection.inputStream.use { input -> FileOutputStream(staging).use { output ->
                    val buffer=ByteArray(64*1024);var total=0L
                    while(true) { val read=input.read(buffer);if(read<0) break;total+=read
                        require(total<=descriptor.downloadBytes) { "Regional pack download is too large" };output.write(buffer,0,read) }
                    require(total==descriptor.downloadBytes) { "Regional pack download was incomplete" }
                } }
            } finally { connection.disconnect() }
            return store.install(descriptor,staging)
        } finally { staging.delete() }
    }
    fun delete(region: String) { store.delete(region) }
    private fun requestText(url: String): String {
        val token=credential()?.takeIf(String::isNotBlank) ?: error("Sign in to check regional road data")
        val connection=open(URL(url))
        try { connection.requestMethod="GET";connection.connectTimeout=10_000;connection.readTimeout=15_000
            connection.setRequestProperty("Authorization","Bearer $token")
            require(connection.responseCode in 200..299) { "Regional catalogue unavailable (HTTP ${connection.responseCode})" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    private companion object { val supportedRegions=setOf("lancashire","merseyside") }
}
