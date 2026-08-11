package io.heckel.ntfy.update

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val contentType: String?
)

data class GitHubRelease(
    val tagName: String,
    val name: String?,
    val assets: List<ReleaseAsset>
)

sealed interface UpdateCheckResult {
    data class UpdateAvailable(
        val release: GitHubRelease,
        val version: String,
        val asset: ReleaseAsset
    ) : UpdateCheckResult

    data object UpToDate : UpdateCheckResult
    data object NoPublishedRelease : UpdateCheckResult
}

class GitHubReleaseClient(
    private val repository: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val githubBaseUrl: HttpUrl = GITHUB_BASE_URL
) {
    private val repositoryPathSegments = requireNotNull(parseRepositoryPathSegments(repository)) {
        "GitHub repository must use the owner/name format"
    }

    fun check(currentVersion: String): UpdateCheckResult {
        val request = Request.Builder()
            .url(repositoryUrl("releases", "latest"))
            .header("User-Agent", "notification-android/$currentVersion")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 404) {
                return UpdateCheckResult.NoPublishedRelease
            }
            if (!response.isSuccessful) {
                throw IOException("GitHub Releases page returned HTTP ${response.code}")
            }

            val tagName = releaseTagFromUrl(response.request.url, repository)
                ?: throw IOException("GitHub latest release redirect did not contain a version tag")
            val latestVersion = normalizeVersion(tagName)
            if (!isNewerVersion(latestVersion, currentVersion)) {
                return UpdateCheckResult.UpToDate
            }

            val asset = ReleaseAsset(
                name = LATEST_APK_ASSET_NAME,
                downloadUrl = repositoryUrl(
                    "releases",
                    "download",
                    tagName,
                    LATEST_APK_ASSET_NAME
                ).toString(),
                contentType = APK_MIME_TYPE
            )
            val release = GitHubRelease(tagName, tagName, listOf(asset))
            return UpdateCheckResult.UpdateAvailable(release, latestVersion, asset)
        }
    }

    private fun githubUrl(vararg pathSegments: String): HttpUrl {
        return githubBaseUrl.newBuilder()
            .apply { pathSegments.forEach(::addPathSegment) }
            .build()
    }

    private fun repositoryUrl(vararg pathSegments: String): HttpUrl {
        return githubUrl(*(repositoryPathSegments + pathSegments).toTypedArray())
    }
}

internal fun releaseTagFromUrl(url: HttpUrl, repository: String): String? {
    val repositorySegments = parseRepositoryPathSegments(repository) ?: return null

    val expectedPrefix = repositorySegments + listOf("releases", "tag")
    val pathSegments = url.pathSegments
    if (pathSegments.size != expectedPrefix.size + 1) return null
    if (pathSegments.take(expectedPrefix.size) != expectedPrefix) return null
    return pathSegments.last().takeIf(VERSION_TAG_REGEX::matches)
}

private fun parseRepositoryPathSegments(repository: String): List<String>? {
    return repository.split('/').takeIf { segments ->
        segments.size == 2 && segments.none(String::isBlank)
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

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val LATEST_APK_ASSET_NAME = "notification-fdroid-release.apk"
private val GITHUB_BASE_URL = "https://github.com/".toHttpUrl()
private val VERSION_TAG_REGEX = Regex("v[0-9]+\\.[0-9]+\\.[0-9]+", RegexOption.IGNORE_CASE)
