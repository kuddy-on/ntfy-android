package io.heckel.ntfy.update

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
    fun ignoresDebugApksAndPrefersFdroidRelease() {
        val assets = listOf(
            ReleaseAsset("notification-debug.apk", "https://example.com/debug.apk", APK_MIME),
            ReleaseAsset("notification-universal-release.apk", "https://example.com/universal.apk", APK_MIME),
            ReleaseAsset("notification-fdroid-release.apk", "https://example.com/fdroid.apk", APK_MIME)
        )

        assertEquals("notification-fdroid-release.apk", selectApkAsset(assets)?.name)
    }

    @Test
    fun returnsNullWhenReleaseHasNoInstallableApk() {
        val assets = listOf(
            ReleaseAsset("source.zip", "https://example.com/source.zip", "application/zip"),
            ReleaseAsset("notification-debug.apk", "https://example.com/debug.apk", APK_MIME)
        )

        assertEquals(null, selectApkAsset(assets))
    }

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
    }
}
