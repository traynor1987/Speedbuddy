package uk.co.traynor.speedbuddy

import org.json.JSONObject
import java.net.URI

data class AvailableRelease(val version: String, val pageUrl: String, val apkUrl: String,
    val sha256: String, val bytes: Long)

/** Only the named asset of a published, stable release is eligible for installation. */
object ReleaseCatalog {
    private const val MAX_APK_BYTES = 350L * 1024 * 1024
    private val versionPattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
    fun isNewer(candidate: String, installed: String): Boolean {
        require(versionPattern.matches(candidate) && versionPattern.matches(installed)) { "Invalid release version" }
        val a = candidate.split('.').map(String::toLong)
        val b = installed.split('.').map(String::toLong)
        return a.zip(b).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
    }

    fun parse(json: String, installed: String): AvailableRelease? {
        val item = JSONObject(json)
        require(!item.optBoolean("draft") && !item.optBoolean("prerelease")) { "Release is not published" }
        val tag = item.getString("tag_name")
        require(tag.startsWith('v')) { "Invalid release tag" }
        val version = tag.drop(1)
        if (!isNewer(version, installed)) return null
        val page = item.getString("html_url")
        require(trustedUrl(page, "https://github.com/traynor1987/Speedbuddy/releases/tag/$tag")) { "Unexpected release page" }
        val assets = item.getJSONArray("assets")
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
            .singleOrNull { it.optString("name") == "SpeedBuddy-release.apk" }
            ?: throw IllegalArgumentException("No signed Speed Buddy APK in this release")
        val url = asset.getString("browser_download_url")
        require(trustedUrl(url, "https://github.com/traynor1987/Speedbuddy/releases/download/$tag/SpeedBuddy-release.apk")) { "Unexpected download address" }
        val digest = asset.optString("digest")
        require(digest.matches(Regex("sha256:[a-fA-F0-9]{64}"))) { "Release has no SHA-256 verification" }
        val bytes = asset.getLong("size")
        require(bytes in 1..MAX_APK_BYTES) { "Unexpected update size" }
        return AvailableRelease(version, page, url, digest.removePrefix("sha256:").lowercase(), bytes)
    }

    private fun trustedUrl(url: String, expected: String): Boolean =
        runCatching { URI(url).normalize().toASCIIString() == expected }.getOrDefault(false)
}
