package uk.co.traynor.speedbuddy

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL


/** Production catalogue and download gateway. Verification and activation stay in [RegionalPackStore]. */
internal class RegionalPackLifecycle(private val context: Context, private val credential: () -> String?,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    data class DownloadProgress(val regionId: String, val stage: Stage, val downloadedBytes: Long, val totalBytes: Long) {
        enum class Stage { DOWNLOADING, VERIFYING_DOWNLOAD, DECOMPRESSING, VERIFYING_DATABASE, ACTIVATING }
        val fraction: Float get()=if(totalBytes==0L) 0f else downloadedBytes.toFloat().div(totalBytes).coerceIn(0f,1f)
        val label: String get()=when(stage) {
            Stage.DOWNLOADING -> "Downloading ${(fraction*100).toInt()}%"
            Stage.VERIFYING_DOWNLOAD -> "Verifying download"
            Stage.DECOMPRESSING -> "Preparing offline road data"
            Stage.VERIFYING_DATABASE -> "Checking regional road data"
            Stage.ACTIVATING -> "Activating verified pack"
        }
    }
    private val store=RegionalPackStore(context)
    fun installed()=store.installed()
    fun inventory()=store.inventory()
    fun catalogue(): List<RegionalPackDescriptor> = requestText("https://api.jtwebsolutions.co.uk/speedbuddy/v1/regions").let(RegionalRoadCatalogue::parse)
        .filter { it.id in supportedRegions }
    fun download(descriptor: RegionalPackDescriptor, onProgress: (DownloadProgress) -> Unit = {}): RegionalPackStore.Installed = store.downloading(descriptor.id) { revision ->
        val staging=store.temporaryDownload()
        try {
            val token=credential()?.takeIf(String::isNotBlank) ?: error("Sign in to download regional road data")
            val connection=open(URL(descriptor.downloadUrl))
            try {
                connection.requestMethod="GET"; connection.connectTimeout=10_000; connection.readTimeout=30_000
                connection.setRequestProperty("Authorization","Bearer $token")
                require(connection.responseCode in 200..299) { "Regional pack download failed (HTTP ${connection.responseCode})" }
                connection.inputStream.use { input -> FileOutputStream(staging).use { output ->
                    val buffer=ByteArray(64*1024);var total=0L;onProgress(DownloadProgress(descriptor.id,DownloadProgress.Stage.DOWNLOADING,total,descriptor.downloadBytes))
                    while(true) { val read=input.read(buffer);if(read<0) break;total+=read
                        require(total<=descriptor.downloadBytes) { "Regional pack download is too large" };output.write(buffer,0,read)
                        onProgress(DownloadProgress(descriptor.id,DownloadProgress.Stage.DOWNLOADING,total,descriptor.downloadBytes)) }
                    require(total==descriptor.downloadBytes) { "Regional pack download was incomplete" }
                } }
            } finally { connection.disconnect() }
            store.installDownloaded(descriptor,staging,revision) { stage -> onProgress(DownloadProgress(descriptor.id,when(stage) {
                RegionalPackStore.InstallStage.VERIFYING_DOWNLOAD -> DownloadProgress.Stage.VERIFYING_DOWNLOAD
                RegionalPackStore.InstallStage.DECOMPRESSING -> DownloadProgress.Stage.DECOMPRESSING
                RegionalPackStore.InstallStage.VERIFYING_DATABASE -> DownloadProgress.Stage.VERIFYING_DATABASE
                RegionalPackStore.InstallStage.ACTIVATING -> DownloadProgress.Stage.ACTIVATING
            },descriptor.downloadBytes,descriptor.downloadBytes)) }
        } finally { store.releaseTemporary(staging) }
    }
    fun delete(region: String)=store.delete(region)
    fun storedBytes()=store.storedBytes()
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
