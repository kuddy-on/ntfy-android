package io.heckel.ntfy.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseClientTest {
    @Test
    fun comparesNumericVersionComponents() {
        assertTrue(isNewerVersion("v1.26.0", "1.25.2"))
        assertTrue(isNewerVersion("1.10.0", "1.9.9"))
        assertFalse(isNewerVersion("1.25.2", "1.25.2-debug"))
        assertFalse(isNewerVersion("1.24.9", "1.25.2"))
    }

    @Test
    fun extractsVersionFromLatestReleaseRedirect() {
        val url = "https://github.com/kuddy-on/ntfy-android/releases/tag/v1.25.3".toHttpUrl()

        assertEquals("v1.25.3", releaseTagFromUrl(url, REPOSITORY))
    }

    @Test
    fun rejectsUnexpectedReleaseRedirect() {
        val wrongRepository = "https://github.com/other/repository/releases/tag/v1.25.3".toHttpUrl()
        val nonVersionTag = "https://github.com/kuddy-on/ntfy-android/releases/tag/latest".toHttpUrl()

        assertEquals(null, releaseTagFromUrl(wrongRepository, REPOSITORY))
        assertEquals(null, releaseTagFromUrl(nonVersionTag, REPOSITORY))
    }

    @Test
    fun checksLatestReleaseWithoutUsingRestApi() {
        var requestedUrl = ""
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestedUrl = chain.request().url.toString()
                Response.Builder()
                    .request(
                        chain.request().newBuilder()
                            .url("https://github.com/$REPOSITORY/releases/tag/v1.25.3")
                            .build()
                    )
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody())
                    .build()
            }
            .build()

        val result = GitHubReleaseClient(REPOSITORY, httpClient).check("1.25.2")

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        result as UpdateCheckResult.UpdateAvailable
        assertEquals("https://github.com/$REPOSITORY/releases/latest", requestedUrl)
        assertEquals("1.25.3", result.version)
        assertEquals("notification-fdroid-release.apk", result.asset.name)
        assertEquals(
            "https://github.com/$REPOSITORY/releases/download/v1.25.3/notification-fdroid-release.apk",
            result.asset.downloadUrl
        )
    }

    companion object {
        private const val REPOSITORY = "kuddy-on/ntfy-android"
    }
}
