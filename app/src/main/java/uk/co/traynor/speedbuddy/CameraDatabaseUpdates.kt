package uk.co.traynor.speedbuddy

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

/** Only an owner-configured, permitted HTTPS ZIP feed is used; no account or tile scraping. */
object CameraDatabaseUpdates {
    private const val PERIODIC="camera-database-daily"
    fun validFeed(value: String): Boolean = runCatching {
        val uri=URI(value)
        uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.fragment==null
    }.getOrDefault(false)
    fun configure(context: Context, feed: String) {
        require(feed.isBlank() || validFeed(feed)) { "Use an HTTPS ZIP address without account credentials" }
        context.getSharedPreferences("settings",0).edit().putString("cameraFeed",feed.trim()).apply()
        val manager=WorkManager.getInstance(context)
        if(feed.isBlank()) manager.cancelUniqueWork(PERIODIC) else manager.enqueueUniquePeriodicWork(PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<CameraDatabaseWorker>(24,TimeUnit.HOURS,6,TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true).build()).build())
    }
    fun now(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork("camera-database-now",
        ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<CameraDatabaseWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
}

class CameraDatabaseWorker(context: Context, parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if(DriveBus.state.value.active) return@withContext Result.retry()
        val feed=applicationContext.getSharedPreferences("settings",0).getString("cameraFeed","").orEmpty()
        if(!CameraDatabaseUpdates.validFeed(feed)) return@withContext Result.failure()
        var connection: HttpURLConnection?=null
        try {
            connection=URI(feed).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects=false
            connection.connectTimeout=15_000;connection.readTimeout=30_000
            connection.setRequestProperty("User-Agent","SpeedBuddy/0.1.5 (owner-configured camera feed)")
            require(connection.responseCode==200) { "Camera source returned ${connection.responseCode}" }
            val bytes=connection.inputStream.use { input ->
                val guarded=object: java.io.FilterInputStream(input) {
                    override fun read(b: ByteArray,off: Int,len: Int): Int {
                        check(!DriveBus.state.value.active) { "Update deferred during driving" }
                        return super.read(b,off,len)
                    }
                }
                BoundedIo.bytes(guarded,32_000_000)
            }
            val batch=LufopAscImporter.inspect(bytes.inputStream())
            if(DriveBus.state.value.active || isStopped) return@withContext Result.retry()
            CameraDb(applicationContext).use { db ->
                val date=batch.archiveDateMs?.let { java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.UK).format(java.util.Date(it)) }
                    ?: "Archive date unknown"
                db.replaceImported(batch.cameras,date)
            }
            Result.success()
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) {
            if(DriveBus.state.value.active) Result.retry() else {
                CameraDb(applicationContext).use { it.recordImportFailure(error.message ?: "Camera update failed") }
                if(error is java.io.IOException) Result.retry() else Result.failure()
            }
        } finally { connection?.disconnect() }
    }
}
