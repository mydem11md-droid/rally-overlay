package com.mythic.rally

import android.app.DownloadManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.util.concurrent.Executors

data class AvailableUpdate(val versionCode: Int, val versionName: String, val notes: String, val apkUrl: String)

class AppUpdater(private val activity: MainActivity) {
    private val prefs = activity.getSharedPreferences("rally_updates", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var downloadId = -1L
    private var progressTask: Runnable? = null
    private var onProgress: ((Int) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onInstalledFile: (() -> Unit)? = null

    fun check(force: Boolean, callback: (AvailableUpdate?) -> Unit, error: (String) -> Unit) {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("last_check", 0L) < 6 * 60 * 60 * 1000L) {
            callback(null)
            return
        }
        prefs.edit().putLong("last_check", now).apply()
        worker.execute {
            try {
                val connection = (URL("https://api.github.com/repos/mydem11md-droid/rally-overlay/releases/latest").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    setRequestProperty("User-Agent", "Rally-Overlay-Android")
                }
                val code = connection.responseCode
                if (code == 403 || code == 429) throw IllegalStateException(activity.getString(R.string.update_rate_limited))
                if (code !in 200..299) throw IllegalStateException(activity.getString(R.string.update_check_failed))
                val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val assets = release.getJSONArray("assets")
                var versionAsset: String? = null
                var apkAsset: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    when (asset.optString("name")) {
                        "version.json" -> versionAsset = asset.optString("browser_download_url")
                        "rally-overlay.apk" -> apkAsset = asset.optString("browser_download_url")
                    }
                }
                val versionUrl = versionAsset ?: throw IllegalStateException(activity.getString(R.string.update_metadata_missing))
                val apkUrl = apkAsset ?: throw IllegalStateException(activity.getString(R.string.update_apk_missing))
                val metaConnection = (URL(versionUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000; readTimeout = 12000
                    setRequestProperty("User-Agent", "Rally-Overlay-Android")
                }
                if (metaConnection.responseCode !in 200..299) throw IllegalStateException(activity.getString(R.string.update_metadata_missing))
                val meta = JSONObject(metaConnection.inputStream.bufferedReader().use { it.readText() })
                val remoteCode = meta.getInt("versionCode")
                val installedVersionCode = activity.packageManager.getPackageInfo(activity.packageName, 0).let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode }
                val update = if (remoteCode > installedVersionCode) AvailableUpdate(
                    remoteCode, meta.getString("versionName"), meta.optString("notes", release.optString("body")), apkUrl
                ) else null
                main.post { callback(update) }
            } catch (e: Exception) {
                val message = if (e is UnknownHostException || e is SocketTimeoutException || e is ConnectException)
                    activity.getString(R.string.update_no_internet)
                else e.message ?: activity.getString(R.string.update_check_failed)
                main.post { error(message) }
            }
        }
    }

    fun download(update: AvailableUpdate, progress: (Int) -> Unit, error: (String) -> Unit, ready: () -> Unit) {
        onProgress = progress
        onError = error
        onInstalledFile = ready
        try {
            val request = DownloadManager.Request(Uri.parse(update.apkUrl))
                .setTitle(activity.getString(R.string.update_downloading))
                .setDescription(update.versionName)
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(activity, android.os.Environment.DIRECTORY_DOWNLOADS, "rally-overlay-update.apk")
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") activity.registerReceiver(receiver, filter)
            downloadId = (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            pollProgress()
        } catch (e: Exception) {
            error(activity.getString(R.string.update_download_failed))
        }
    }

    private fun pollProgress() {
        progressTask = object : Runnable {
            override fun run() {
                if (downloadId < 0) return
                val query = DownloadManager.Query().setFilterById(downloadId)
                (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).query(query)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val soFar = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        if (total > 0) onProgress?.invoke(((soFar * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
                main.postDelayed(this, 500)
            }
        }
        main.post(progressTask!!)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
            main.removeCallbacks(progressTask ?: return)
            runCatching { activity.unregisterReceiver(this) }
            val file = File(activity.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "rally-overlay-update.apk")
            try {
                val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(downloadId)
                var expectedBytes = -1L
                val status = manager.query(query).use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        expectedBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    } else -1
                }
                if (status != DownloadManager.STATUS_SUCCESSFUL || !file.exists() ||
                    file.length() < 100_000L || expectedBytes <= 0L || file.length() != expectedBytes) {
                    throw IllegalStateException(activity.getString(R.string.update_download_failed))
                }
                validateApk(file)
                prefs.edit().putString("pending_apk", file.absolutePath).apply()
                install(file)
            } catch (e: Exception) {
                onError?.invoke(e.message ?: activity.getString(R.string.update_invalid_apk))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File) {
        val archiveFlags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = activity.packageManager.getPackageArchiveInfo(file.absolutePath, archiveFlags)
            ?: throw IllegalStateException(activity.getString(R.string.update_invalid_apk))
        if (archive.packageName != activity.packageName) throw IllegalStateException(activity.getString(R.string.update_wrong_package))
        val current = activity.packageManager.getPackageInfo(activity.packageName, archiveFlags)
        val oldSigners = if (Build.VERSION.SDK_INT >= 28) current.signingInfo?.apkContentsSigners else current.signatures
        val newSigners = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        val oldSet = oldSigners?.map { it.toCharsString() }?.toSet()
        val newSet = newSigners?.map { it.toCharsString() }?.toSet()
        if (oldSet.isNullOrEmpty() || oldSet != newSet) throw IllegalStateException(activity.getString(R.string.update_signature_mismatch))
    }

    private fun install(file: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            prefs.edit().putString("pending_apk", file.absolutePath).apply()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        prefs.edit().remove("pending_apk").apply()
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
        onInstalledFile?.invoke()
    }

    fun resumePendingInstall() {
        val path = prefs.getString("pending_apk", null) ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) return
        val file = File(path)
        if (file.exists()) runCatching { validateApk(file); install(file) }.onFailure {
            onError?.invoke(it.message ?: activity.getString(R.string.update_invalid_apk))
        } else prefs.edit().remove("pending_apk").apply()
    }
}
