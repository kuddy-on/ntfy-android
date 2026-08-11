package io.heckel.ntfy.update

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class ReleaseAsset(
    val name: String,
    @SerializedName("browser_download_url") val downloadUrl: String,
    @SerializedName("content_type") val contentType: String?
)

data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String,
    val name: String?,
    val assets: List<ReleaseAsset>
)

sealed interface UpdateCheckResult {
    data class UpdateAvailable(
        val release: GitHubRelease,
        val version: String,
        val asset: ReleaseAsset
    ) : UpdateCheckResult

    data class ReleaseHasNoApk(val version: String) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object NoPublishedRelease : UpdateCheckResult
}

class GitHubReleaseClient(
    private val repository: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson()
) {
    fun check(currentVersion: String): UpdateCheckResult {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repository/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "notification-android/$currentVersion")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 404) {
                return UpdateCheckResult.NoPublishedRelease
            }
            if (!response.isSuccessful) {
                throw IOException("GitHub Releases API returned HTTP ${response.code}")
            }

            val release = gson.fromJson(response.body.string(), GitHubRelease::class.java)
                ?: throw IOException("GitHub Releases API returned an empty response")
            val latestVersion = normalizeVersion(release.tagName)
            if (!isNewerVersion(latestVersion, currentVersion)) {
                return UpdateCheckResult.UpToDate
            }

            val asset = selectApkAsset(release.assets)
                ?: return UpdateCheckResult.ReleaseHasNoApk(latestVersion)
            return UpdateCheckResult.UpdateAvailable(release, latestVersion, asset)
        }
    }
}

internal fun normalizeVersion(version: String): String {
    return version.trim().removePrefix("v").removePrefix("V").substringBefore('-')
}

internal fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
    val latest = versionNumbers(latestVersion)
    val current = versionNumbers(currentVersion)
    if (latest.isEmpty() || current.isEmpty()) return false

    val componentCount = maxOf(latest.size, current.size)
    for (index in 0 until componentCount) {
        val latestPart = latest.getOrElse(index) { 0L }
        val currentPart = current.getOrElse(index) { 0L }
        if (latestPart != currentPart) return latestPart > currentPart
    }
    return false
}

private fun versionNumbers(version: String): List<Long> {
    return normalizeVersion(version)
        .split('.')
        .mapNotNull { component -> component.toLongOrNull() }
}

internal fun selectApkAsset(assets: List<ReleaseAsset>): ReleaseAsset? {
    return assets
        .asSequence()
        .filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) ||
                asset.contentType.equals(APK_MIME_TYPE, ignoreCase = true)
        }
        .filterNot { it.name.contains("debug", ignoreCase = true) }
        .maxByOrNull(::apkAssetScore)
}

private fun apkAssetScore(asset: ReleaseAsset): Int {
    val name = asset.name.lowercase()
    var score = 0
    if ("fdroid" in name) score += 100
    if ("universal" in name) score += 50
    if ("release" in name) score += 25
    if (asset.contentType.equals(APK_MIME_TYPE, ignoreCase = true)) score += 10
    return score
}

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
