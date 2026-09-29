package uk.co.traynor.speedbuddy

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Explicit, parked-only checks. Downloads are never started by the driving service. */
class UpdateClient(private val context: Context) {
    suspend fun check(): AvailableRelease? = withContext(Dispatchers.IO) {
        val connection = open("https://api.github.com/repos/traynor1987/Speedbuddy/releases/latest")
        try {
            if (connection.responseCode == 404) throw IllegalStateException("No signed release has been published yet")
            require(connection.responseCode == 200) { "GitHub is unavailable (${connection.responseCode})" }
            val json = connection.inputStream.bufferedReader().use { it.readText().take(512_001).also { text ->
                require(text.length <= 512_000) { "Unexpected release response" }
            } }
            val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
            ReleaseCatalog.parse(json, installed)
        } finally { connection.disconnect() }
    }

    suspend fun download(release: AvailableRelease, onProgress: suspend (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(folder, "SpeedBuddy-release.apk")
        val temporary = File(folder, "download.pending.apk")
        temporary.delete()
        try {
            val connection = open(release.apkUrl, followReleaseRedirects = true)
            try {
                require(connection.responseCode == 200) { "Download unavailable (${connection.responseCode})" }
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                connection.inputStream.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            require(received <= release.bytes) { "Update is larger than advertised" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            withContext(Dispatchers.Main) { onProgress((received * 100 / release.bytes).toInt()) }
                        }
                    }
                }
                require(received == release.bytes) { "Update download was incomplete" }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(actual == release.sha256) { "Update failed integrity check" }
                verifyPackage(temporary)
                require(temporary.renameTo(target)) { "Could not prepare update" }
                target
            } finally { connection.disconnect() }
        } finally { temporary.delete() }
    }

    private fun verifyPackage(file: File) {
        val manager = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IllegalArgumentException("Downloaded file is not an Android app")
        val installed = manager.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) { "Update belongs to a different app" }
        require(archive.longVersionCode > installed.longVersionCode) { "Update is not newer than this installation" }
        val installedSigners = installed.signingInfo?.apkContentsSigners.orEmpty()
        val updateSigners = archive.signingInfo?.apkContentsSigners.orEmpty()
        require(installedSigners.isNotEmpty() && updateSigners.isNotEmpty() &&
            installedSigners.size == updateSigners.size &&
            updateSigners.all { candidate -> installedSigners.any {
                MessageDigest.isEqual(it.toByteArray(), candidate.toByteArray())
            } }) { "Signing key differs from this installation. Back up your data before changing builds." }
    }

    private fun open(address: String, followReleaseRedirects: Boolean = false): HttpURLConnection {
        var current = address
        repeat(6) { hop ->
            val parsed = URL(current)
            require(parsed.protocol == "https" && parsed.host in setOf(
                "api.github.com", "github.com", "release-assets.githubusercontent.com")) { "Unsafe update address" }
            val connection = parsed.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "SpeedBuddy-Android-Updates")
            connection.setRequestProperty("Accept", if (followReleaseRedirects) "application/octet-stream" else "application/vnd.github+json")
            if (connection.responseCode !in 300..399) return connection
            val next = connection.getHeaderField("Location")
            connection.disconnect()
            require(hop < 5 && !next.isNullOrBlank()) { "Too many update redirects" }
            current = URL(parsed, next).toString()
        }
        error("Could not reach update")
    }
}
