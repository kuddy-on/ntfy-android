package io.heckel.ntfy.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.util.ANDROID_APP_MIME_TYPE
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class AppUpdateManager(private val activity: AppCompatActivity) {
    private val preferences by lazy {
        activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val downloadManager by lazy {
        activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }
    private val releaseClient by lazy {
        GitHubReleaseClient(BuildConfig.UPDATE_REPOSITORY)
    }
    private var receiverRegistered = false
    private var permissionDialogVisible = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, NO_DOWNLOAD_ID)
            if (completedId == pendingDownloadId()) {
                handlePendingDownload(showFailure = true)
            }
        }
    }

    fun start() {
        if (!BuildConfig.SELF_UPDATE_AVAILABLE || receiverRegistered) return
        ContextCompat.registerReceiver(
            activity,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    fun stop() {
        if (!receiverRegistered) return
        activity.unregisterReceiver(downloadReceiver)
        receiverRegistered = false
    }

    fun onResume() {
        if (!BuildConfig.SELF_UPDATE_AVAILABLE) return
        handlePendingDownload(showFailure = false)
        installPendingUpdate()
    }

    fun checkForUpdates() {
        if (!BuildConfig.SELF_UPDATE_AVAILABLE) return
        Toast.makeText(activity, R.string.update_check_checking, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    releaseClient.check(BuildConfig.VERSION_NAME)
                }
                if (
                    activity.isFinishing ||
                    activity.isDestroyed ||
                    !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) return@launch
                showCheckResult(result)
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                if (!activity.isFinishing && !activity.isDestroyed) {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.update_check_failed_title)
                        .setMessage(
                            activity.getString(
                                R.string.update_check_failed_message,
                                e.localizedMessage ?: e.javaClass.simpleName
                            )
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun showCheckResult(result: UpdateCheckResult) {
        when (result) {
            is UpdateCheckResult.UpdateAvailable -> {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.update_check_available_title)
                    .setMessage(
                        activity.getString(
                            R.string.update_check_available_message,
                            BuildConfig.VERSION_NAME,
                            result.version
                        )
                    )
                    .setNegativeButton(R.string.update_check_later, null)
                    .setPositiveButton(R.string.update_check_download) { _, _ ->
                        enqueueDownload(result.version, result.asset)
                    }
                    .show()
            }
            is UpdateCheckResult.ReleaseHasNoApk -> {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.update_check_no_apk_title)
                    .setMessage(activity.getString(R.string.update_check_no_apk_message, result.version))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            UpdateCheckResult.UpToDate -> {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.update_check_up_to_date_title)
                    .setMessage(activity.getString(R.string.update_check_up_to_date_message, BuildConfig.VERSION_NAME))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            UpdateCheckResult.NoPublishedRelease -> {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.update_check_no_release_title)
                    .setMessage(R.string.update_check_no_release_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun enqueueDownload(version: String, asset: ReleaseAsset) {
        handlePendingDownload(showFailure = false)
        if (pendingDownloadId() != NO_DOWNLOAD_ID) {
            Toast.makeText(activity, R.string.update_download_already_running, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val downloadsDirectory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IOException("External downloads directory is unavailable")
            val updateDirectory = File(downloadsDirectory, UPDATE_DIRECTORY)
            if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
                throw IOException("Cannot create update download directory")
            }

            val fileName = safeApkFileName(asset.name, version)
            val destination = File(updateDirectory, fileName)
            if (destination.exists() && !destination.delete()) {
                throw IOException("Cannot replace an existing update package")
            }

            val request = DownloadManager.Request(asset.downloadUrl.toUri())
                .setTitle(activity.getString(R.string.update_download_title, version))
                .setDescription(activity.getString(R.string.update_download_description))
                .setMimeType(ANDROID_APP_MIME_TYPE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(
                    activity,
                    Environment.DIRECTORY_DOWNLOADS,
                    "$UPDATE_DIRECTORY/$fileName"
                )

            val downloadId = downloadManager.enqueue(request)
            preferences.edit {
                putLong(KEY_DOWNLOAD_ID, downloadId)
                putString(KEY_DOWNLOAD_PATH, destination.absolutePath)
            }
            Toast.makeText(activity, R.string.update_download_started, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to enqueue app update download", e)
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_download_failed_title)
                .setMessage(
                    activity.getString(
                        R.string.update_download_failed_message,
                        e.localizedMessage ?: e.javaClass.simpleName
                    )
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun handlePendingDownload(showFailure: Boolean) {
        val downloadId = pendingDownloadId()
        if (downloadId == NO_DOWNLOAD_ID) return

        try {
            val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
            if (cursor == null) {
                clearPendingDownload()
                return
            }
            cursor.use {
                if (!it.moveToFirst()) {
                    clearPendingDownload()
                    return
                }
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val path = preferences.getString(KEY_DOWNLOAD_PATH, null)
                        clearPendingDownload()
                        if (path != null && File(path).isFile) {
                            preferences.edit { putString(KEY_INSTALL_PATH, path) }
                            installPendingUpdate()
                        } else if (showFailure) {
                            showDownloadFailed(activity.getString(R.string.update_download_file_missing))
                        }
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        clearPendingDownload()
                        Log.w(TAG, "App update download failed with reason $reason")
                        if (showFailure) showDownloadFailed(reason.toString())
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to inspect app update download", e)
            if (showFailure) showDownloadFailed(e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    private fun showDownloadFailed(reason: String) {
        if (
            activity.isFinishing ||
            activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_download_failed_title)
            .setMessage(activity.getString(R.string.update_download_failed_message, reason))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun installPendingUpdate() {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val path = preferences.getString(KEY_INSTALL_PATH, null) ?: return
        val apk = File(path)
        if (!apk.isFile) {
            preferences.edit { remove(KEY_INSTALL_PATH) }
            return
        }

        if (!activity.packageManager.canRequestPackageInstalls()) {
            showInstallPermissionDialog()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                activity,
                BuildConfig.APPLICATION_ID + ".provider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, ANDROID_APP_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(intent)
            preferences.edit { remove(KEY_INSTALL_PATH) }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open the APK installer", e)
            showDownloadFailed(activity.getString(R.string.update_installer_not_found))
        }
    }

    private fun showInstallPermissionDialog() {
        if (permissionDialogVisible || activity.isFinishing || activity.isDestroyed) return
        permissionDialogVisible = true
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_install_permission_title)
            .setMessage(R.string.update_install_permission_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.update_install_permission_settings) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${activity.packageName}".toUri()
                )
                activity.startActivity(intent)
            }
            .setOnDismissListener { permissionDialogVisible = false }
            .show()
    }

    private fun pendingDownloadId(): Long {
        return preferences.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD_ID)
    }

    private fun clearPendingDownload() {
        preferences.edit {
            remove(KEY_DOWNLOAD_ID)
            remove(KEY_DOWNLOAD_PATH)
        }
    }

    private fun safeApkFileName(assetName: String, version: String): String {
        val sanitized = assetName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(MAX_FILE_NAME_LENGTH)
            .ifBlank { "notification-$version.apk" }
        return if (sanitized.endsWith(".apk", ignoreCase = true)) sanitized else "$sanitized.apk"
    }

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val PREFERENCES_NAME = "app_updates"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_DOWNLOAD_PATH = "download_path"
        private const val KEY_INSTALL_PATH = "install_path"
        private const val UPDATE_DIRECTORY = "updates"
        private const val MAX_FILE_NAME_LENGTH = 120
        private const val NO_DOWNLOAD_ID = -1L
    }
}
